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

package mx.agromarin.james.odoo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.mailet.Attribute;
import org.apache.mailet.AttributeValue;
import org.apache.mailet.Mail;
import org.apache.mailet.base.test.FakeMail;
import org.apache.mailet.base.test.FakeMailetConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class OdooInboundTest {
    @TempDir
    Path directory;

    private HttpServer odoo;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String answer = "{}";
    private final List<JsonNode> received = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private Path token;

    @BeforeEach
    void startOdoo() throws IOException {
        odoo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        odoo.createContext("/mail/inbound/7", exchange -> {
            received.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        odoo.start();
        token = directory.resolve("token");
        Files.writeString(token, "s3cret\n");
    }

    @AfterEach
    void stopOdoo() {
        odoo.stop(0);
    }

    private OdooInbound mailet(int maxAttempts) throws Exception {
        OdooInbound mailet = new OdooInbound();
        mailet.init(FakeMailetConfig.builder()
            .mailetName("OdooInbound")
            .setProperty("url", "http://127.0.0.1:" + odoo.getAddress().getPort() + "/mail/inbound/7")
            .setProperty("tokenFile", token.toString())
            .setProperty("maxAttempts", String.valueOf(maxAttempts))
            .build());
        return mailet;
    }

    private static Mail mail() throws Exception {
        return FakeMail.builder()
            .name("m1")
            .sender("zed@supplier.test")
            .recipients("maria.orozco@agromarin.test", "ventas@agromarin.test")
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                .addFrom("zed@supplier.test")
                .addToRecipient("maria.orozco@agromarin.test")
                .setSubject("Contrato")
                .setText("Hola"))
            .state("root")
            .build();
    }

    @Test
    void anAcceptedMessageIsPostedAsTheMailgatePostsItAndConsumed() throws Exception {
        Mail mail = mail();
        mailet(10).service(mail);

        assertThat(mail.getState()).isEqualTo(Mail.GHOST);
        assertThat(authorizations).containsExactly("Bearer s3cret");
        JsonNode envelope = received.get(0);
        assertThat(envelope.get("sender").asText()).isEqualTo("zed@supplier.test");
        assertThat(envelope.get("recipients")).extracting(JsonNode::asText)
            .containsExactly("maria.orozco@agromarin.test", "ventas@agromarin.test");
        String raw = new String(Base64.getDecoder().decode(envelope.get("message").asText()),
            StandardCharsets.UTF_8);
        assertThat(raw).contains("Subject: Contrato").contains("Hola");
    }

    @Test
    void aNullSenderIsPostedAsAnEmptySender() throws Exception {
        Mail mail = FakeMail.builder().name("dsn").recipient("maria.orozco@agromarin.test")
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder().setSubject("bounce").setText("x"))
            .build();
        mailet(10).service(mail);

        assertThat(received.get(0).get("sender").asText()).isEmpty();
    }

    @Test
    void theMailgatesStatusMappingHoldsForEveryAnswer() {
        assertThat(OdooInbound.classify(200, new byte[0]).fate()).isEqualTo(OdooInbound.Fate.DELIVERED);
        assertThat(OdooInbound.classify(202, new byte[0]).fate()).isEqualTo(OdooInbound.Fate.DELIVERED);
        assertThat(OdooInbound.classify(409, json("duplicate_event")).fate()).isEqualTo(OdooInbound.Fate.DELIVERED);
        assertThat(OdooInbound.classify(409, json("other")).fate()).isEqualTo(OdooInbound.Fate.RETRIED);
        assertThat(OdooInbound.classify(422, json("no_route")).fate()).isEqualTo(OdooInbound.Fate.BOUNCED);
        assertThat(OdooInbound.classify(422, json("processing_failed")).fate()).isEqualTo(OdooInbound.Fate.BOUNCED);
        assertThat(OdooInbound.classify(422, json("other")).fate()).isEqualTo(OdooInbound.Fate.RETRIED);
        assertThat(OdooInbound.classify(413, new byte[0]).fate()).isEqualTo(OdooInbound.Fate.BOUNCED);
        assertThat(OdooInbound.classify(401, json("authentication_failed")).fate()).isEqualTo(OdooInbound.Fate.RETRIED);
        assertThat(OdooInbound.classify(503, "not json".getBytes()).fate()).isEqualTo(OdooInbound.Fate.RETRIED);
        assertThat(OdooInbound.classify(302, new byte[0]).fate()).isEqualTo(OdooInbound.Fate.RETRIED);
    }

    @Test
    void aRefusedTokenIsRetriedAndCountedNotBounced() throws Exception {
        status.set(401);
        answer = "{\"error\": \"authentication_failed\"}";
        Mail mail = mail();
        mailet(3).service(mail);

        assertThat(mail.getState()).isEqualTo("odoo-retry");
        assertThat(mail.getAttribute(OdooInbound.ATTEMPTS).map(a -> (Object) a.getValue().value())).contains(1);
    }

    @Test
    void theLastAllowedAttemptBouncesInsteadOfRetrying() throws Exception {
        status.set(503);
        Mail mail = mail();
        mail.setAttribute(new Attribute(OdooInbound.ATTEMPTS, AttributeValue.of(2)));
        mailet(3).service(mail);

        assertThat(mail.getState()).isEqualTo("odoo-bounce");
        assertThat(deliveryError(mail))
            .isEqualTo("554 5.4.7 The receiving system stayed unavailable, so delivery was abandoned.");
        assertThat(mail.getErrorMessage()).isEqualTo("The receiving system stayed unavailable, so delivery was abandoned.");
    }

    @Test
    void anUnroutedAddressBouncesAtOnce() throws Exception {
        status.set(422);
        answer = "{\"error\": \"no_route\", \"detail\": \"nobody@agromarin.test\"}";
        Mail mail = mail();
        mailet(10).service(mail);

        assertThat(mail.getState()).isEqualTo("odoo-bounce");
        assertThat(deliveryError(mail)).isEqualTo("550 5.1.1 There is no mailbox for this address.");
        assertThat(mail.getErrorMessage())
            .isEqualTo("There is no mailbox for this address.")
            .doesNotContain("nobody@agromarin.test");
    }

    @Test
    void anUnreachableOdooIsRetried() throws Exception {
        OdooInbound mailet = mailet(10);
        odoo.stop(0);
        Mail mail = mail();
        mailet.service(mail);

        assertThat(mail.getState()).isEqualTo("odoo-retry");
        assertThat(mail.getErrorMessage()).startsWith("cannot reach Odoo");
    }

    @Test
    void aMissingTokenFileIsRetriedNotBounced() throws Exception {
        OdooInbound mailet = mailet(10);
        Files.delete(token);
        Mail mail = mail();
        mailet.service(mail);

        assertThat(mail.getState()).isEqualTo("odoo-retry");
        assertThat(received).isEmpty();
    }

    @Test
    void eachPermanentRefusalCarriesItsEnhancedStatusCode() {
        assertThat(OdooInbound.classify(422, json("no_route")).status()).isEqualTo("5.1.1");
        assertThat(OdooInbound.classify(422, json("processing_failed")).status()).isEqualTo("5.3.0");
        assertThat(OdooInbound.classify(413, new byte[0]).status()).isEqualTo("5.3.4");
    }

    @Test
    void odoosDetailNeverReachesTheSendersNotice() {
        byte[] answer = "{\"error\": \"processing_failed\", \"detail\": \"Traceback in mail.thread\"}".getBytes();
        OdooInbound.Verdict verdict = OdooInbound.classify(422, answer);
        assertThat(verdict.notice()).doesNotContain("Traceback");
        assertThat(verdict.reason()).contains("Traceback");
    }

    private static String deliveryError(Mail mail) {
        return mail.getAttribute(OdooInbound.DELIVERY_ERROR).map(a -> (String) a.getValue().value()).orElse(null);
    }

    private static byte[] json(String error) {
        return ("{\"error\": \"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
