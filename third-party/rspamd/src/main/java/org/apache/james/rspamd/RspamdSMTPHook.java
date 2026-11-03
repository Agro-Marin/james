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

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import jakarta.inject.Inject;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.apache.james.lifecycle.api.LifecycleUtil;
import org.apache.james.protocols.smtp.MailEnvelope;
import org.apache.james.protocols.smtp.SMTPRetCode;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.protocols.smtp.hook.MessageHook;
import org.apache.james.rspamd.client.RspamdHttpClient;
import org.apache.james.rspamd.model.AnalysisResult;
import org.apache.james.server.core.MailImpl;
import org.apache.james.smtpserver.AddDefaultAttributesMessageHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks Rspamd about a message at the end of DATA, so what Rspamd rejects is refused while its sender is still
 * connected (554 5.7.1) and what it defers is deferred (451 4.7.1): nothing is bounced afterwards to a sender that
 * may be forged. Mail from an authenticated or relaying client is ours and not scanned. When Rspamd cannot answer,
 * the message is accepted. It is a {@link MessageHook}: those run before every JamesMessageHook, the core handlers'
 * SendMailHandler, which queues the mail, among them.
 */
public class RspamdSMTPHook implements MessageHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(RspamdSMTPHook.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final RspamdHttpClient rspamd;
    private final AddDefaultAttributesMessageHook transportAttributes = new AddDefaultAttributesMessageHook();

    @Inject
    public RspamdSMTPHook(RspamdHttpClient rspamd) {
        this.rspamd = rspamd;
    }

    @Override
    public HookResult onMessage(SMTPSession session, MailEnvelope envelope) {
        if (session.getUsername() != null || session.isRelayingAllowed()) {
            return HookResult.DECLINED;
        }
        AnalysisResult result;
        MailImpl mail = null;
        try {
            mail = scanned(session, envelope);
            result = rspamd.checkV2(mail).block(TIMEOUT);
        } catch (Exception error) {
            LOGGER.warn("Rspamd did not answer for {}, accepting it unscanned", session.getSessionID(), error);
            return HookResult.DECLINED;
        } finally {
            LifecycleUtil.dispose(mail);
        }
        if (result == null) {
            return HookResult.DECLINED;
        }
        return switch (result.getAction()) {
            case REJECT -> {
                LOGGER.info("Rspamd rejects a message from {} in session {} (score {})",
                    envelope.getMaybeSender().asString(), session.getSessionID(), result.getScore());
                yield refusal(HookReturnCode.deny(), SMTPRetCode.TRANSACTION_FAILED,
                    DSNStatus.getStatus(DSNStatus.PERMANENT, DSNStatus.SECURITY_AUTH) + " Message rejected as spam");
            }
            case SOFT_REJECT, GREY_LIST -> refusal(HookReturnCode.denySoft(), SMTPRetCode.LOCAL_ERROR,
                DSNStatus.getStatus(DSNStatus.TRANSIENT, DSNStatus.SECURITY_AUTH) + " Try again later");
            default -> HookResult.DECLINED;
        };
    }

    private MailImpl scanned(SMTPSession session, MailEnvelope envelope) throws IOException, MessagingException {
        MailImpl mail = MailImpl.builder()
            .name("rspamd-" + session.getSessionID())
            .sender(envelope.getMaybeSender())
            .addRecipients(envelope.getRecipients())
            .mimeMessage(new MimeMessage(Session.getInstance(new Properties()), envelope.getMessageInputStream()))
            .build();
        transportAttributes.onMessage(session, mail);
        return mail;
    }

    private static HookResult refusal(HookReturnCode code, String reply, String description) {
        return HookResult.builder()
            .hookReturnCode(code)
            .smtpReturnCode(reply)
            .smtpDescription(description)
            .build();
    }
}
