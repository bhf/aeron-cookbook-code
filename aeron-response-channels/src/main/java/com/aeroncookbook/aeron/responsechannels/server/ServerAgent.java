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

import com.aeroncookbook.aeron.responsechannels.Constants;
import io.aeron.Aeron;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import org.agrona.CloseHelper;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class ServerAgent implements Agent
{
    private final Aeron aeron;
    private final Logger log = LoggerFactory.getLogger(ServerAgent.class);
    private final ServerAdapter serverAdapter;
    private Subscription requestSubscription;

    private final Map<Long, Publication> clientConnections = new Long2ObjectHashMap<>();
    private final Map<Long, Long> pendingPubIdToCorrelationId = new Long2LongHashMap(128);
    private final Set<Long> pendingResponsePublications = new LongHashSet();

    public ServerAgent(final Aeron aeron, final ShutdownSignalBarrier barrier)
    {
        this.aeron = aeron;
        this.serverAdapter = new ServerAdapter(barrier, clientConnections);
    }

    @Override
    public void onStart()
    {
        log.info("Server starting");
        requestSubscription = aeron.addSubscription(
                "aeron:udp?endpoint=localhost:10001|tags=1",
                Constants.REQUEST_STREAM_ID,
                this::handleConnect,
                this::handleDisconnect);
    }

    private void handleConnect(final Image image)
    {
        final var correlationId = image.correlationId();
        log.info("Handling connect on correlationId {}", correlationId);
        pendingResponsePublications.add(correlationId);
    }

    private void handleDisconnect(final Image image)
    {
        final var correlationId = image.correlationId();
        log.info("Handling disconnect on correlationId {}", correlationId);
        final Publication publication = clientConnections.remove(correlationId);
        CloseHelper.quietClose(publication);
    }

    @Override
    public int doWork()
    {
        while (!requestSubscription.isConnected())
        {
            aeron.context().idleStrategy().idle();
        }

        processPendingResponseChannelCreations();
        processPendingPublications();

        return requestSubscription.poll(serverAdapter, 1);
    }

    /**
     * Process the pending {@link Publication}s we created in an async fashion.
     */
    private void processPendingPublications()
    {
        pendingPubIdToCorrelationId.forEach((registrationId, correlationId) ->
        {
            try
            {
                final var pendingPub = aeron.getPublication(registrationId);

                if (null != pendingPub)
                {
                    log.info("Pending publication for correlation Id {} is now not null, isConnected={} ",
                        correlationId, pendingPub.isConnected());

                    clientConnections.put(correlationId, pendingPub);
                    pendingPubIdToCorrelationId.remove(correlationId);
                }
            }
            catch (final Exception e)
            {
                log.error("Error whilst processing pending publications", e);
            }
        });
    }

    /**
     * Create the response publication in an async style to
     * prevent recursive calls from the available image handler callback.
     */
    private void processPendingResponseChannelCreations()
    {
        pendingResponsePublications.forEach(correlationId ->
        {
            final var responseCorrelation = "response-correlation-id=" + correlationId;
            final var responseTag = "tags=1," + correlationId;

            final Long registrationId = aeron.asyncAddPublication(
                "aeron:udp?endpoint=localhost:10001|"+responseCorrelation+"|"+responseTag,
                Constants.RESPONSE_STREAM_ID);

            log.info("Creating response publication for correlation Id {}, registrationId {}",
                correlationId, registrationId);

            pendingPubIdToCorrelationId.put(registrationId, correlationId);
            pendingResponsePublications.remove(correlationId);
        });
    }

    @Override
    public void onClose()
    {
        serverAdapter.closePublication();
    }

    @Override
    public String roleName()
    {
        return "server";
    }
}
