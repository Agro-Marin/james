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

import static org.apache.james.imap.api.process.ImapSession.MDC_KEY;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.james.imap.api.ConnectionCheck;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.protocols.api.CommandDetectionSession;
import org.apache.james.protocols.api.ProxyInformation;
import org.apache.james.util.MDCBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class HAProxyMessageHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HAProxyMessageHandler.class);
    private static final AttributeKey<CommandDetectionSession> SESSION_ATTRIBUTE_KEY = AttributeKey.valueOf("ImapSession");
    public static final AttributeKey<ProxyInformation> PROXY_INFO = AttributeKey.valueOf("proxyInfo");

    private final Set<ConnectionCheck> connectionChecks;
    // What the client sent after its PROXY header, held until the connection check ends; event loop only.
    private final List<Object> heldMessages = new ArrayList<>();
    private boolean checkingConnection;

    public HAProxyMessageHandler(Set<ConnectionCheck> connectionChecks) {
        this.connectionChecks = connectionChecks;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HAProxyMessage) {
            handleHAProxyMessage(ctx, (HAProxyMessage) msg);
        } else if (checkingConnection) {
            heldMessages.add(msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseHeldMessages();
        super.channelInactive(ctx);
    }

    private void replayHeldMessages(ChannelHandlerContext ctx) throws Exception {
        checkingConnection = false;
        List<Object> held = new ArrayList<>(heldMessages);
        heldMessages.clear();
        held.forEach(ctx::fireChannelRead);
        ctx.channel().config().setAutoRead(true);
        super.channelReadComplete(ctx);
    }

    private void releaseHeldMessages() {
        heldMessages.forEach(ReferenceCountUtil::release);
        heldMessages.clear();
    }

    private void handleHAProxyMessage(ChannelHandlerContext ctx, HAProxyMessage haproxyMsg) {
        ChannelPipeline pipeline = ctx.pipeline();
        ImapSession imapSession = (ImapSession) pipeline.channel().attr(SESSION_ATTRIBUTE_KEY).get();
        if (haproxyMsg.proxiedProtocol().equals(HAProxyProxiedProtocol.TCP4) || haproxyMsg.proxiedProtocol().equals(HAProxyProxiedProtocol.TCP6)) {

            InetSocketAddress sourceIP = new InetSocketAddress(haproxyMsg.sourceAddress(), haproxyMsg.sourcePort());
            ctx.channel().attr(PROXY_INFO).set(
                new ProxyInformation(
                    sourceIP,
                    new InetSocketAddress(haproxyMsg.destinationAddress(), haproxyMsg.destinationPort())));

            LOGGER.info("Connection from {} runs through {} proxy", haproxyMsg.sourceAddress(), haproxyMsg.destinationAddress());
            // Refresh MDC info to account for proxying
            MDCBuilder boundMDC = IMAPMDCContext.boundMDC(ctx);

            // Pause reading while the connection check is in progress to prevent IMAP
            // commands from being processed before the check completes. Bytes read along with the PROXY header are
            // already in the pipeline: they are held, or a banned client's LOGIN would run before its ban.
            ctx.channel().config().setAutoRead(false);
            checkingConnection = true;
            performConnectionCheck(sourceIP)
                .then(Mono.fromRunnable(() -> {
                    if (imapSession != null) {
                        imapSession.setAttribute(MDC_KEY, boundMDC);
                    }
                }))
                .doFinally(any -> haproxyMsg.release())
                .subscribe(any -> {
                }, error -> ctx.executor().execute(() -> {
                    // Still checking: whatever else arrives is held too, and released with the channel.
                    releaseHeldMessages();
                    ctx.pipeline().fireExceptionCaught(error);
                }), () -> ctx.executor().execute(Throwing.runnable(() -> replayHeldMessages(ctx))));

        } else {
            haproxyMsg.release();
            throw new IllegalArgumentException("Only TCP4/TCP6 are supported when using PROXY protocol.");
        }
    }

    private Mono<Void> performConnectionCheck(InetSocketAddress realClientIp) {
        if (!connectionChecks.isEmpty()) {
            return Flux.fromIterable(connectionChecks)
                .concatMap(connectionCheck -> connectionCheck.validate(realClientIp))
                .then();
        }
        return Mono.empty();
    }
}
