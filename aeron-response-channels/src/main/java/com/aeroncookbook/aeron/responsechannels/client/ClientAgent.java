/*
 * Copyright 2019-2023 Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aeroncookbook.aeron.responsechannels.client;

import com.aeroncookbook.aeron.responsechannels.Constants;
import com.aeroncookbook.sbe.MessageHeaderEncoder;
import com.aeroncookbook.sbe.RpcRequestMethodEncoder;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.UUID.randomUUID;
import static org.agrona.CloseHelper.quietClose;

public class ClientAgent implements Agent
{
    private final Logger log = LoggerFactory.getLogger(ClientAgent.class);
    private final ExpandableDirectByteBuffer buffer;
    private final Aeron aeron;
    private final ClientAdapter clientAdapter;
    private final RpcRequestMethodEncoder requestMethod;
    private final MessageHeaderEncoder headerEncoder;

    private State state;
    private ExclusivePublication requestPublication;
    private Subscription responseSubscription;

    public ClientAgent(final Aeron aeron, final ShutdownSignalBarrier barrier)
    {
        this.clientAdapter = new ClientAdapter(barrier);
        this.aeron = aeron;
        this.buffer = new ExpandableDirectByteBuffer(250);
        this.requestMethod = new RpcRequestMethodEncoder();
        this.headerEncoder = new MessageHeaderEncoder();
    }

    @Override
    public void onStart()
    {
        log.info("Client starting");
        state = State.AWAITING_OUTBOUND_CONNECT;
    }

    @Override
    public int doWork()
    {
        switch (state)
        {
            case AWAITING_OUTBOUND_CONNECT:
                createRequestPublication();
                state = State.CONNECTED;
                break;
            case CONNECTED:
                if (!responseSubscription.isConnected())
                {
                    awaitResponseSubscription();
                }

                sendMessage();
                state = State.AWAITING_RESPONSE;
                break;
            case AWAITING_RESPONSE:
                final var res = responseSubscription.poll(clientAdapter, 100);

                if (res > 0)
                {
                    state = State.CONNECTED;
                }
                break;
            default:
                break;
        }
        return 0;
    }

    /**
     * This creates the publication we send requests on to the server.
     */
    private void createRequestPublication()
    {
        requestPublication = aeron.addExclusivePublication(
                "aeron:udp?endpoint=localhost:10001", Constants.REQUEST_STREAM_ID);

        while (!requestPublication.isConnected())
        {
            aeron.context().idleStrategy().idle();
        }

        log.info("request publication connected");

        responseSubscription = aeron.addSubscription(
            "aeron:udp?control-mode=response|control=localhost:10002",
                Constants.RESPONSE_STREAM_ID);
    }

    private void sendMessage()
    {
        final String input = "string to be made uppercase";
        final String correlation = randomUUID().toString();
        requestMethod.wrapAndApplyHeader(buffer, 0, headerEncoder);
        requestMethod.parameters(input);
        requestMethod.correlation(correlation);

        log.info("sending: {} with correlation {}", input, correlation);
        send(buffer, headerEncoder.encodedLength() + requestMethod.encodedLength());
        log.info("sent message to server, awaiting response");
    }

    /**
     * Wait for the response subscription to connect.
     */
    private void awaitResponseSubscription()
    {
        log.info("awaiting response subscription connect");

        while (!responseSubscription.isConnected())
        {
            aeron.context().idleStrategy().idle();
        }

        log.info("response subscription connected");
    }

    @Override
    public void onClose()
    {
        quietClose(requestPublication);
        quietClose(responseSubscription);
    }

    @Override
    public String roleName()
    {
        return "client";
    }

    private void send(final DirectBuffer buffer, final int length)
    {
        int retries = 3;

        do
        {
            final long result = requestPublication.offer(buffer, 0, length);
            if (result > 0)
            {
                break;
            }
            else
            {
                log.info("aeron returned {} on offer", result);
            }
        }
        while (--retries > 0);
    }

    enum State
    {
        AWAITING_OUTBOUND_CONNECT,
        CONNECTED,
        AWAITING_RESPONSE
    }
}
