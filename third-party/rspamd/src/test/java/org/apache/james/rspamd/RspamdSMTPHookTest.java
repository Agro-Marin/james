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

package org.apache.james.rspamd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLSession;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.protocols.api.ProtocolSession;
import org.apache.james.protocols.smtp.MailEnvelopeImpl;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.protocols.smtp.utils.BaseFakeSMTPSession;
import org.apache.james.rspamd.client.RspamdClientConfiguration;
import org.apache.james.rspamd.client.RspamdHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

class RspamdSMTPHookTest {
    private HttpServer rspamd;
    private volatile String action = "no action";
    private final List<Headers> requests = new CopyOnWriteArrayList<>();
    private RspamdSMTPHook hook;

    @BeforeEach
    void startRspamd() throws IOException {
        rspamd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rspamd.createContext("/checkV2", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.add(exchange.getRequestHeaders());
            byte[] body = ("{\"action\": \"" + action + "\", \"score\": 15.5, \"required_score\": 15, \"symbols\": {}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        rspamd.start();
        URL url = new URL("http://127.0.0.1:" + rspamd.getAddress().getPort());
        hook = new RspamdSMTPHook(new RspamdHttpClient(new RspamdClientConfiguration(url, "", Optional.of(5))));
    }

    @AfterEach
    void stopRspamd() {
        rspamd.stop(0);
    }

    private static MailEnvelopeImpl envelope() throws Exception {
        MailEnvelopeImpl envelope = new MailEnvelopeImpl();
        envelope.setSender(MaybeSender.getMailSender("zed@supplier.test"));
        envelope.setRecipients(List.of(new MailAddress("maria@agromarin.test")));
        try (OutputStream out = envelope.getMessageOutputStream()) {
            out.write(("From: zed@supplier.test\r\nTo: maria@agromarin.test\r\nSubject: Oferta\r\n\r\n"
                + "XJS*C4JDBQADN1.NSBN3*2IDNEN*GTUBE-STANDARD-ANTI-UBE-TEST-EMAIL*C.34X\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        return envelope;
    }

    private HookResult deliver(Username username, boolean relaying) throws Exception {
        return hook.onMessage(session(username, relaying), envelope());
    }

    @Test
    void whatRspamdRejectsIsRefusedWhileTheSenderIsConnected() throws Exception {
        action = "reject";

        HookResult result = deliver(null, false);

        assertThat(result.getResult()).isEqualTo(HookReturnCode.deny());
        assertThat(result.getSmtpRetCode()).isEqualTo("554");
        assertThat(result.getSmtpDescription()).startsWith("5.7.1 ");
    }

    @Test
    void whatRspamdDefersIsDeferred() throws Exception {
        for (String deferral : List.of("soft reject", "greylist")) {
            action = deferral;

            HookResult result = deliver(null, false);

            assertThat(result.getResult()).isEqualTo(HookReturnCode.denySoft());
            assertThat(result.getSmtpDescription()).startsWith("4.7.1 ");
        }
    }

    @Test
    void whatRspamdOnlyMarksIsAccepted() throws Exception {
        for (String marking : List.of("no action", "add header", "rewrite subject")) {
            action = marking;

            assertThat(deliver(null, false).getResult()).isEqualTo(HookReturnCode.declined());
        }
    }

    @Test
    void rspamdHearsTheConnectingAddressAndTheHelo() throws Exception {
        deliver(null, false);

        assertThat(requests).singleElement().satisfies(headers -> {
            assertThat(headers.getFirst("IP")).isEqualTo("127.0.0.9");
            assertThat(headers.getFirst("HELO")).isEqualTo("mx.supplier.test");
            assertThat(headers.getFirst("From")).isEqualTo("zed@supplier.test");
        });
    }

    @Test
    void ourOwnClientsAreNotScanned() throws Exception {
        action = "reject";

        assertThat(deliver(Username.of("maria@agromarin.test"), true).getResult()).isEqualTo(HookReturnCode.declined());
        assertThat(deliver(null, true).getResult()).isEqualTo(HookReturnCode.declined());
        assertThat(requests).isEmpty();
    }

    @Test
    void anUnreachableRspamdLetsTheMessageIn() throws Exception {
        action = "reject";
        rspamd.stop(0);

        assertThat(deliver(null, false).getResult()).isEqualTo(HookReturnCode.declined());
    }

    private static SMTPSession session(Username username, boolean relaying) {
        Map<ProtocolSession.AttachmentKey<?>, Object> connection = new HashMap<>();
        connection.put(SMTPSession.CURRENT_HELO_NAME, "mx.supplier.test");
        return new BaseFakeSMTPSession() {
            @Override
            public Username getUsername() {
                return username;
            }

            @Override
            public boolean isRelayingAllowed() {
                return relaying;
            }

            @Override
            public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress("127.0.0.9", 52000);
            }

            @Override
            public String getSessionID() {
                return "s1";
            }

            @Override
            public Optional<SSLSession> getSSLSession() {
                return Optional.empty();
            }

            @Override
            public <T> Optional<T> getAttachment(AttachmentKey<T> key, State state) {
                return key.convert(connection.get(key));
            }
        };
    }
}
