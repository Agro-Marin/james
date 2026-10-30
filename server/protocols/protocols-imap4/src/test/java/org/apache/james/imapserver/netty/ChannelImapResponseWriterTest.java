/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.imapserver.netty;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.james.imap.message.BytesBackedLiteral;
import org.apache.james.imap.message.SequencedLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

class ChannelImapResponseWriterTest {
    private static final String FETCH = "* 1 FETCH (BODY[] {4}\r\nbody)\r\n";
    private static final String TAGGED_OK = "A1 OK FETCH completed.\r\n";
    private static final String BYE = "* BYE IMAP4rev1 Server logging out\r\nA2 OK LOGOUT completed.\r\n";

    private DefaultEventLoopGroup eventLoops;
    private StringBuffer received;
    private CountDownLatch receivedAll;
    private CountDownLatch serverSideClosed;
    private Channel server;
    private Channel client;

    @BeforeEach
    void setUp() throws Exception {
        eventLoops = new DefaultEventLoopGroup(1);
        received = new StringBuffer();
        receivedAll = new CountDownLatch(1);
        serverSideClosed = new CountDownLatch(1);
        LocalAddress address = new LocalAddress(ChannelImapResponseWriterTest.class);
        server = new ServerBootstrap()
            .group(eventLoops)
            .channel(LocalServerChannel.class)
            .childHandler(new ChannelInitializer<LocalChannel>() {
                @Override
                protected void initChannel(LocalChannel channel) {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf bytes = (ByteBuf) msg;
                            received.append(bytes.toString(US_ASCII));
                            bytes.release();
                            if (received.length() >= FETCH.length() + TAGGED_OK.length()) {
                                receivedAll.countDown();
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            serverSideClosed.countDown();
                        }
                    });
                }
            })
            .bind(address).sync().channel();
        client = new Bootstrap()
            .group(eventLoops)
            .channel(LocalChannel.class)
            .handler(new ChannelInboundHandlerAdapter())
            .connect(address).sync().channel();
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close().sync();
        server.close().sync();
        eventLoops.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    @Test
    void aResponseWrittenOnTheEventLoopShouldNotOvertakeALiteralQueuedFromAnotherThread() throws Exception {
        ChannelImapResponseWriter writer = new ChannelImapResponseWriter(client, false);
        CountDownLatch literalQueued = new CountDownLatch(1);
        client.eventLoop().execute(() -> {
            try {
                literalQueued.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer.write(TAGGED_OK.getBytes(US_ASCII));
        });

        writer.write(new SequencedLiteral(List.of(
            BytesBackedLiteral.of("* 1 FETCH (BODY[] {4}\r\n".getBytes(US_ASCII)),
            BytesBackedLiteral.of("body".getBytes(US_ASCII)),
            BytesBackedLiteral.of(")\r\n".getBytes(US_ASCII)))));
        literalQueued.countDown();

        assertThat(receivedAll.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(received.toString()).isEqualTo(FETCH + TAGGED_OK);
    }

    @Test
    void aCloseOnTheEventLoopShouldNotDropAResponseWaitingBehindAQueuedOne() throws Exception {
        ChannelImapResponseWriter writer = new ChannelImapResponseWriter(client, false);
        CountDownLatch fetchQueued = new CountDownLatch(1);
        client.eventLoop().execute(() -> {
            try {
                fetchQueued.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer.write(BYE.getBytes(US_ASCII));
            ChannelImapResponseWriter.closeAfterWrites(client);
        });

        writer.write(FETCH.getBytes(US_ASCII));
        fetchQueued.countDown();

        assertThat(serverSideClosed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(received.toString()).isEqualTo(FETCH + BYE);
    }
}
