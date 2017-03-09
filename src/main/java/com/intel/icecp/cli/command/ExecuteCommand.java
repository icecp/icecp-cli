/*
 * ******************************************************************************
 *
 *  INTEL CONFIDENTIAL
 *
 *  Copyright 2013 - 2016 Intel Corporation All Rights Reserved.
 *
 *  The source code contained or described herein and all documents related to the
 *  source code ("Material") are owned by Intel Corporation or its suppliers or
 *  licensors. Title to the Material remains with Intel Corporation or its
 *  suppliers and licensors. The Material contains trade secrets and proprietary
 *  and confidential information of Intel or its suppliers and licensors. The
 *  Material is protected by worldwide copyright and trade secret laws and treaty
 *  provisions. No part of the Material may be used, copied, reproduced, modified,
 *  published, uploaded, posted, transmitted, distributed, or disclosed in any way
 *  without Intel's prior express written permission.
 *
 *  No license under any patent, copyright, trade secret or other intellectual
 *  property right is granted to or conferred upon you by disclosure or delivery of
 *  the Materials, either expressly, by implication, inducement, estoppel or
 *  otherwise. Any license under such intellectual property rights must be express
 *  and approved by Intel in writing.
 *
 *  Unless otherwise agreed by Intel in writing, you may not remove or alter this
 *  notice or any other notice embedded in Materials by Intel or Intel's suppliers
 *  or licensors in any way.
 *
 * *********************************************************************
 */

package com.intel.icecp.cli.command;

import com.intel.icecp.cli.exceptions.FailedResponseException;
import com.intel.icecp.core.management.Channels;
import com.intel.icecp.rpc.CommandRequest;
import com.intel.icecp.rpc.CommandResponse;
import com.intel.icecp.rpc.Rpc;
import com.intel.icecp.rpc.RpcClient;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class calls RpcClient to execute commands.
 *
 */
public class ExecuteCommand {
    private ExecuteCommand() {
    }

    public static Object execute(Channels channels, URI serverUri, String cmd, int timeout, Object... params)
            throws InterruptedException, ExecutionException, NoSuchElementException, FailedResponseException, TimeoutException {

        // create RPC client and call
        RpcClient client = Rpc.newClient(channels, serverUri);
        CommandRequest request = CommandRequest.from(cmd, params);
        CommandResponse response = client.call(request).get(timeout, TimeUnit.SECONDS);

        // check response
        if (response.err) {
            throw new FailedResponseException((String) response.out);
        }

        return response.out;
    }
}