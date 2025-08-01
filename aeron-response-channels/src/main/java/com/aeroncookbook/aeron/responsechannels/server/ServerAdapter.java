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

package com.aeroncookbook.aeron.responsechannels.server;

import com.aeroncookbook.sbe.MessageHeaderDecoder;
import com.aeroncookbook.sbe.MessageHeaderEncoder;
import com.aeroncookbook.sbe.RpcRequestMethodDecoder;
import com.aeroncookbook.sbe.RpcResponseEventEncoder;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.agrona.CloseHelper.quietClose;

public class ServerAdapter implements FragmentHandler
{
    private final Logger log = LoggerFactory.getLogger(ServerAdapter.class);
    private final ShutdownSignalBarrier barrier;
    private final RpcRequestMethodDecoder requestMethod;
    private final MessageHeaderEncoder headerEncoder;
    private final MessageHeaderDecoder headerDecoder;
    private final RpcResponseEventEncoder responseEvent;
    private final ExpandableDirectByteBuffer buffer;
    private final Map<Long, Publication> clientConnections;

    public ServerAdapter(final ShutdownSignalBarrier barrier,
        final Map<Long, Publication> clientConnections)
    {
        this.clientConnections = clientConnections;
        this.requestMethod = new RpcRequestMethodDecoder();
        this.responseEvent = new RpcResponseEventEncoder();
        this.headerDecoder = new MessageHeaderDecoder();
        this.headerEncoder = new MessageHeaderEncoder();
        this.buffer = new ExpandableDirectByteBuffer(512);
        this.barrier = barrier;
    }

    @Override
    public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        headerDecoder.wrap(buffer, offset);
        final int headerLength = headerDecoder.encodedLength();
        final int actingLength = headerDecoder.blockLength();
        final int actingVersion = headerDecoder.version();

        log.info("Got message {}", headerDecoder.templateId());
        final var image = (Image)header.context();

        if (headerDecoder.templateId() == RpcRequestMethodDecoder.TEMPLATE_ID)
        {
            requestMethod.wrap(buffer, offset + headerLength,
                actingLength, actingVersion);
            final String parameters = requestMethod.parameters();
            final String correlation = requestMethod.correlation();
            respond(parameters, correlation, image);
        }
    }

    private void respond(final String parameters, final String correlation, final Image image)
    {
        final String returnValue = parameters.toUpperCase();

        log.info("responding on correlation {} with value {}", correlation, returnValue);

        responseEvent.wrapAndApplyHeader(buffer, 0, headerEncoder);
        responseEvent.result(returnValue);
        responseEvent.correlation(correlation);

        final var publication = clientConnections.get(image.correlationId());

        if (null == publication)
        {
            log.warn("Couldn't get response channel publication for image {}", image);
        }

        int retries = 3;
        do
        {
            final long result = publication.offer(buffer, 0, headerEncoder.encodedLength() +
                responseEvent.encodedLength());
            if (result > 0)
            {
                //shutdown once the result is sent
                barrier.signal();
                break;
            }
            else
            {
                log.warn("aeron returned {}", result);
            }
        }
        while (--retries > 0);
    }


    public void closePublication()
    {
        for (final var publication : clientConnections.values())
        {
            quietClose(publication);
        }
    }
}
