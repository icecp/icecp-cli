/*
 * Copyright (c) 2017 Intel Corporation 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.icecp.cli.util;

import com.intel.icecp.core.Channel;
import com.intel.icecp.core.Message;
import com.intel.icecp.core.Metadata;
import com.intel.icecp.core.channels.ChannelProvider;
import com.intel.icecp.core.channels.Token;
import com.intel.icecp.core.management.Channels;
import com.intel.icecp.core.messages.BytesMessage;
import com.intel.icecp.core.metadata.Format;
import com.intel.icecp.core.metadata.Persistence;
import com.intel.icecp.core.metadata.formats.BytesFormat;
import com.intel.icecp.core.metadata.formats.JsonFormat;
import com.intel.icecp.core.misc.ChannelIOException;
import com.intel.icecp.core.misc.ChannelLifetimeException;
import com.intel.icecp.core.misc.Configuration;
import com.intel.icecp.node.pipeline.implementations.MessageFormattingPipeline;
import com.intel.icecp.node.utils.MetadataUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Implementation of {@link Channels}; this is a minimal implementation used for providing channels while sharing a
 * small (see {@link #CORE_POOL_SIZE}) thread pool. It expects channel providers to be SPI-provided or registered
 * statically on {@link ImplementationLoader}.
 *
 */
public class PartialChannelsImpl implements Channels {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int DEFAULT_OPEN_TIMEOUT = 10;
    private static final int CORE_POOL_SIZE = 3;
    private final Map<URI, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, ChannelProvider> registered = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pool;
    private final Configuration configuration;

    public PartialChannelsImpl(Configuration configuration) {
        this.pool = Executors.newScheduledThreadPool(CORE_POOL_SIZE);
        this.configuration = configuration;

        // load configuration; TODO is this necessary?
        try {
            configuration.load();
        } catch (ChannelIOException e) {
            throw new IllegalStateException("The configuration should be loadable", e);
        }

        // load providers
        for (ChannelProvider cp : ImplementationLoader.load(ChannelProvider.class)) {
            this.register(cp.scheme(), cp);
        }
    }

    ExecutorService pool() {
        return pool;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(String scheme, ChannelProvider implementation) {
        LOGGER.debug("Registering new scheme: {}", scheme);
        registered.put(scheme, implementation);
        implementation.start(pool, configuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(String scheme) {
        throw new UnsupportedOperationException("Use shutdown instead.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        LOGGER.debug("Shutting down all channels");

        // close all channels
        for (Channel channel : channels.values()) {
            try {
                channel.close();
            } catch (ChannelLifetimeException ex) {
                LOGGER.error("Unable to close channel: {}", channel, ex);
            }
        }

        // stop all transports
        registered.values().forEach(ChannelProvider::stop);

        // remove all providers
        registered.clear();

        // shut down the thread pool
        pool.shutdownNow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelProvider get(String scheme) {
        return registered.get(scheme);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends Message> Channel<T> openChannel(URI uri, Class<T> messageType, Persistence persistence, Metadata... metadata) throws ChannelLifetimeException {
        return openChannel(uri, Token.of(messageType), persistence, metadata);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Message> Channel<T> openChannel(URI uri, Token<T> messageType, Persistence persistence, Metadata... metadata) throws ChannelLifetimeException {
        LOGGER.debug("Opening channel: {}", uri);

        // retrieve provider
        ChannelProvider provider = get(uri.getScheme());
        if (provider == null) {
            throw new ChannelLifetimeException("No provider found for scheme on: " + uri);
        }

        // discover format
        Format messageFormat = MetadataUtils.find(Format.class, metadata);
        if (messageFormat == null) {
            messageFormat = messageType.isAssignableFrom(BytesMessage.class) ? new BytesFormat() : new JsonFormat<>(messageType);
        }
        LOGGER.debug("Using format {} for channel: {}", messageFormat.getClass().getSimpleName(), uri);

        // build channel and open it
        @SuppressWarnings("unchecked")
        Channel<T> channel = provider.build(uri, MessageFormattingPipeline.create(messageType, messageFormat), persistence, metadata);
        try {
            channel.open().get(DEFAULT_OPEN_TIMEOUT, TimeUnit.SECONDS);
            channels.put(uri, channel);
            return channel;
        } catch (TimeoutException | InterruptedException | ExecutionException ex) {
            throw new ChannelLifetimeException("Failed to open channel: " + uri, ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public URI[] getOpenChannels() {
        Set<URI> uris = channels.values().stream().filter(Channel::isOpen).map(Channel::getName).collect(Collectors.toSet());
        return uris.toArray(new URI[]{});
    }
}
