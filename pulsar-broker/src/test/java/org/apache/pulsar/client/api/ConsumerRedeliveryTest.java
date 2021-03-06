/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.impl.ConsumerImpl;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Sets;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertEquals;

public class ConsumerRedeliveryTest extends ProducerConsumerBase {
    @BeforeClass
    @Override
    protected void setup() throws Exception {
        conf.setManagedLedgerCacheEvictionFrequency(0.1);
        super.internalSetup();
        super.producerBaseSetup();
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    /**
     * It verifies that redelivered messages are sorted based on the ledger-ids.
     * <pre>
     * 1. client publishes 100 messages across 50 ledgers
     * 2. broker deliveres 100 messages to consumer
     * 3. consumer ack every alternative message and doesn't ack 50 messsages
     * 4. broker sorts replay messages based on ledger and redelivers messages ledger by ledger
     * </pre>
     * @throws Exception
     */
    @Test
    public void testOrderedRedelivery() throws Exception {
        String topic = "persistent://my-property/my-ns/redelivery";

        conf.setManagedLedgerMaxEntriesPerLedger(2);
        conf.setManagedLedgerMinLedgerRolloverTimeMinutes(0);

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer().topic(topic)
                .producerName("my-producer-name");
        Producer<byte[]> producer = producerBuilder.create();
        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer().topic(topic).subscriptionName("s1")
                .subscriptionType(SubscriptionType.Shared);
        ConsumerImpl<byte[]> consumer1 = (ConsumerImpl<byte[]>) consumerBuilder.subscribe();

        final int totalMsgs = 100;

        for (int i = 0; i < totalMsgs; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }


        int consumedCount = 0;
        Set<MessageId> messageIds = Sets.newHashSet();
        for (int i = 0; i < totalMsgs; i++) {
            Message<byte[]> message = consumer1.receive(5, TimeUnit.SECONDS);
            if (message != null && (consumedCount % 2) == 0) {
                consumer1.acknowledge(message);
            } else {
                messageIds.add(message.getMessageId());
            }
            consumedCount += 1;
        }
        assertEquals(totalMsgs, consumedCount);

        // redeliver all unack messages
        consumer1.redeliverUnacknowledgedMessages(messageIds);

        MessageIdImpl lastMsgId = null;
        for (int i = 0; i < totalMsgs / 2; i++) {
            Message<byte[]> message = consumer1.receive(5, TimeUnit.SECONDS);
            MessageIdImpl msgId = (MessageIdImpl) message.getMessageId();
            if (lastMsgId != null) {
                assertTrue(lastMsgId.getLedgerId() <= msgId.getLedgerId(), "lastMsgId: " + lastMsgId + " -- msgId: " + msgId);
            }
            lastMsgId = msgId;
        }

        // close consumer so, this consumer's unack messages will be redelivered to new consumer
        consumer1.close();

        Consumer<byte[]> consumer2 = consumerBuilder.subscribe();
        lastMsgId = null;
        for (int i = 0; i < totalMsgs / 2; i++) {
            Message<byte[]> message = consumer2.receive(5, TimeUnit.SECONDS);
            MessageIdImpl msgId = (MessageIdImpl) message.getMessageId();
            if (lastMsgId != null) {
                assertTrue(lastMsgId.getLedgerId() <= msgId.getLedgerId());
            }
            lastMsgId = msgId;
        }

        producer.close();
        consumer2.close();
    }

}
