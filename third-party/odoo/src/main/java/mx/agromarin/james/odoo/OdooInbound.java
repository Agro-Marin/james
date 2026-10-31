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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import jakarta.mail.MessagingException;

import org.apache.james.core.MailAddress;
import org.apache.mailet.Attribute;
import org.apache.mailet.AttributeName;
import org.apache.mailet.AttributeValue;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Hands a message to Odoo's /mail/inbound receiver, as odoo-mailgate.py does for Postfix.
 *
 * <p>Odoo's answer decides the message's fate exactly as the mailgate maps it to a
 * sysexits status: 2xx and a 409 duplicate are delivered, a 422 no_route or
 * processing_failed and a 413 are bounced, and everything else (a refused token, a 5xx,
 * no connection) is retried through {@code retryProcessor} until {@code maxAttempts}.
 */
public class OdooInbound extends GenericMailet {
    public static final AttributeName ATTEMPTS = AttributeName.of("odoo-inbound.attempts");
    public static final AttributeName DELIVERY_ERROR = AttributeName.of("delivery-error");

    private static final Logger LOGGER = LoggerFactory.getLogger(OdooInbound.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Fate { DELIVERED, BOUNCED, RETRIED }

    public record Verdict(Fate fate, int replyCode, String status, String notice, String reason) {
        static Verdict delivered() {
            return new Verdict(Fate.DELIVERED, 250, "2.0.0", "", "");
        }

        static Verdict retried(String reason) {
            return new Verdict(Fate.RETRIED, 451, "4.4.1", "", reason);
        }

        String smtpReply() {
            return replyCode + " " + status + " " + notice;
        }
    }

    private URI url;
    private Path tokenFile;
    private Duration timeout;
    private int maxAttempts;
    private String retryProcessor;
    private String bounceProcessor;
    private HttpClient client;

    @Override
    public void init() throws MessagingException {
        url = URI.create(required("url"));
        if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
            throw new MessagingException("url must be an http(s) URL: " + url);
        }
        tokenFile = Path.of(required("tokenFile"));
        timeout = Duration.ofSeconds(positive("timeoutSeconds", 60));
        maxAttempts = positive("maxAttempts", 10);
        retryProcessor = getInitParameter("retryProcessor", "odoo-retry");
        bounceProcessor = getInitParameter("bounceProcessor", "odoo-bounce");
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    private int positive(String name, int fallback) throws MessagingException {
        String value = getInitParameter(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException error) {
            // reported below
        }
        throw new MessagingException(name + " must be a positive integer: " + value);
    }

    private String required(String name) throws MessagingException {
        String value = getInitParameter(name);
        if (value == null || value.isBlank()) {
            throw new MessagingException("OdooInbound needs the init parameter " + name);
        }
        return value.strip();
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        int attempt = attempts(mail) + 1;
        Verdict verdict = deliver(mail);
        switch (verdict.fate()) {
            case DELIVERED -> mail.setState(Mail.GHOST);
            case BOUNCED -> bounce(mail, verdict);
            case RETRIED -> {
                if (attempt >= maxAttempts) {
                    bounce(mail, new Verdict(Fate.BOUNCED, 554, "5.4.7",
                        "The receiving system stayed unavailable, so delivery was abandoned.",
                        "gave up after " + attempt + " attempts: " + verdict.reason()));
                    return;
                }
                LOGGER.warn("Odoo did not take {} (attempt {} of {}): {}",
                    mail.getName(), attempt, maxAttempts, verdict.reason());
                mail.setAttribute(new Attribute(ATTEMPTS, AttributeValue.of(attempt)));
                mail.setErrorMessage(verdict.reason());
                mail.setState(retryProcessor);
            }
        }
    }

    private void bounce(Mail mail, Verdict verdict) {
        LOGGER.warn("Odoo refused {} for good ({}): {}", mail.getName(), verdict.status(), verdict.reason());
        mail.setAttribute(new Attribute(DELIVERY_ERROR, AttributeValue.of(verdict.smtpReply())));
        mail.setErrorMessage(verdict.notice());
        mail.setState(bounceProcessor);
    }

    private static int attempts(Mail mail) {
        return mail.getAttribute(ATTEMPTS)
            .map(attribute -> attribute.getValue().value())
            .filter(Integer.class::isInstance)
            .map(Integer.class::cast)
            .orElse(0);
    }

    Verdict deliver(Mail mail) throws MessagingException {
        String token;
        try {
            token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            return Verdict.retried("cannot read token file: " + error.getMessage());
        }
        if (token.isEmpty()) {
            return Verdict.retried("the token file is empty");
        }
        HttpRequest request = HttpRequest.newBuilder(url)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(envelope(mail)))
            .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException error) {
            return Verdict.retried("cannot reach Odoo: " + error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Verdict.retried("interrupted while waiting for Odoo");
        }
        return classify(response.statusCode(), response.body());
    }

    static byte[] envelope(Mail mail) throws MessagingException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            mail.getMessage().writeTo(raw);
        } catch (IOException error) {
            throw new MessagingException("cannot serialize " + mail.getName(), error);
        }
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("sender", mail.getMaybeSender().asString(""));
        var recipients = envelope.putArray("recipients");
        for (MailAddress recipient : mail.getRecipients()) {
            recipients.add(recipient.asString());
        }
        envelope.put("message", Base64.getEncoder().encodeToString(raw.toByteArray()));
        try {
            return JSON.writeValueAsBytes(envelope);
        } catch (IOException error) {
            throw new MessagingException("cannot encode the envelope of " + mail.getName(), error);
        }
    }

    static Verdict classify(int status, byte[] body) {
        if (status >= 200 && status < 300) {
            return Verdict.delivered();
        }
        JsonNode problem = parse(body);
        String error = problem.path("error").asText("");
        String detail = problem.path("detail").asText("");
        if (status == 409 && error.equals("duplicate_event")) {
            return Verdict.delivered();
        }
        if (status == 422 && error.equals("no_route")) {
            return new Verdict(Fate.BOUNCED, 550, "5.1.1", "There is no mailbox for this address.",
                "no Odoo alias accepts this message: " + detail);
        }
        if (status == 422 && error.equals("processing_failed")) {
            return new Verdict(Fate.BOUNCED, 554, "5.3.0", "The receiving system could not process this message.",
                "processing failed: " + detail);
        }
        if (status == 413) {
            return new Verdict(Fate.BOUNCED, 552, "5.3.4", "The message is larger than the receiving system accepts.",
                "the message is larger than Odoo accepts");
        }
        return Verdict.retried("odoo answered " + status + " " + error + " " + detail);
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : JSON.createObjectNode();
        } catch (IOException error) {
            return JSON.createObjectNode();
        }
    }

    @Override
    public String getMailetInfo() {
        return "OdooInbound: delivers to Odoo's /mail/inbound receiver";
    }
}
