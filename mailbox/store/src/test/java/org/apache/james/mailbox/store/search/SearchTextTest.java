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

package org.apache.james.mailbox.store.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import jakarta.mail.Flags;

import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.model.ByteContent;
import org.apache.james.mailbox.model.TestId;
import org.apache.james.mailbox.model.TestMessageId;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.store.extractor.JsoupTextExtractor;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.junit.jupiter.api.Test;

class SearchTextTest {
    private static MailboxMessage message(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return SimpleMailboxMessage.builder()
            .messageId(TestMessageId.of(1))
            .threadId(ThreadId.fromBaseMessageId(TestMessageId.of(1)))
            .mailboxId(TestId.of(1))
            .uid(MessageUid.of(1))
            .modseq(ModSeq.first())
            .internalDate(new Date())
            .size(bytes.length)
            .bodyStartOctet(content.indexOf("\r\n\r\n") + 4)
            .content(new ByteContent(bytes))
            .flags(new Flags())
            .build();
    }

    private static SearchText text(String content) throws Exception {
        return SearchText.of(message(content), new JsoupTextExtractor(), (attachment, session) -> null, null);
    }

    @Test
    void bodiesShouldHoldTheDecodedTextPartsInTheMatchersCase() throws Exception {
        SearchText text = text("Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + "dGVzdG1haWw=\r\n"
            + "--b\r\nContent-Type: application/octet-stream\r\n\r\nbinary\r\n"
            + "--b--\r\n");

        assertThat(text.bodies()).contains("TESTMAIL").doesNotContain("BINARY");
    }

    @Test
    void headersShouldHoldTheUnfoldedValueOfAHeaderCriterion() throws Exception {
        SearchText text = text("Subject: a long\r\n subject\r\n\r\nbody\r\n");

        assertThat(text.headers()).contains(SearchText.headerNeedle("a long subject"));
    }

    @Test
    void headersShouldHoldEachAddressAsTheAddressCriterionFormatsIt() throws Exception {
        SearchText text = text("To: \"Doe, Jane\" <jane@example.com>, grp: bob@example.com;\r\n\r\nbody\r\n");

        assertThat(text.headers())
            .contains(SearchText.headerNeedle(MessageSearches.encodeAndUnscramble(
                new Mailbox("Doe, Jane", "jane", "example.com"))))
            .contains(SearchText.headerNeedle("bob@example.com"));
    }

    @Test
    void headersShouldHoldTheFieldBodiesOfAFullTextSearch() throws Exception {
        SearchText text = text("Subject: café\r\n\r\nbody\r\n");

        assertThat(text.headers()).contains(SearchText.textNeedle("café"));
    }

    @Test
    void noAttachmentShouldLeaveTheAttachmentColumnsEmpty() throws Exception {
        SearchText text = text("Subject: s\r\n\r\nbody\r\n");

        assertThat(text.attachments()).isEmpty();
        assertThat(text.attachmentNames()).isEmpty();
    }
}
