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

package org.apache.james.mailbox.store;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.store.streaming.MimePart;
import org.junit.jupiter.api.Test;

class MimePartEncapsulatedMessageTest {
    static final String SUBMESSAGE = "From: sub@domain.org\r\n"
        + "Subject: submsg\r\n"
        + "\r\n"
        + "Hello world\r\n";
    static final String MESSAGE_RFC822 = "From: user@domain.org\r\n"
        + "Content-Type: message/rfc822\r\n"
        + "\r\n"
        + SUBMESSAGE;
    static final String DIGEST = "From: sub@domain.org\r\n"
        + "Content-Type: multipart/digest; boundary=\"foo\"\r\n"
        + "\r\n"
        + "prologue\r\n"
        + "\r\n"
        + "--foo \r\n"
        + "\r\n"
        + "From: m1@example.com\r\n"
        + "Subject: m1\r\n"
        + "\r\n"
        + "m1 body\r\n"
        + "\r\n"
        + "--foo \r\n"
        + "X-Mime: m2 header\r\n"
        + "\r\n"
        + "From: m2@example.com\r\n"
        + "Subject: m2\r\n"
        + "\r\n"
        + "m2 body\r\n"
        + "\r\n"
        + "--foo--\r\n"
        + "\r\n"
        + "epilogue\r\n";
    static final String MESSAGE_RFC822_DIGEST = "From: user@domain.org\r\n"
        + "Content-Type: message/rfc822\r\n"
        + "\r\n"
        + DIGEST;

    @Test
    void partOneOfAMessageRfc822ShouldBeTheWholeEncapsulatedMessage() throws Exception {
        assertThat(body(MESSAGE_RFC822, 1)).isEqualTo(SUBMESSAGE);
    }

    @Test
    void headerAndTextOfPartOneShouldBeThoseOfTheEncapsulatedMessage() throws Exception {
        MimePart part = locate(MESSAGE_RFC822, 1);

        assertThat(part.messageHeaders()).extracting(Header::getName).containsExactly("From", "Subject");
        assertThat(new String(part.messageBody(), US_ASCII)).isEqualTo("Hello world\r\n");
    }

    @Test
    void partOneOneShouldBeTheBodyOfANonMultipartEncapsulatedMessage() throws Exception {
        assertThat(body(MESSAGE_RFC822, 1, 1)).isEqualTo("Hello world\r\n");
    }

    @Test
    void partsOfAnEncapsulatedDigestShouldBeItsBodyParts() throws Exception {
        assertThat(body(MESSAGE_RFC822_DIGEST, 1, 1)).isEqualTo("From: m1@example.com\r\nSubject: m1\r\n\r\nm1 body\r\n");
        assertThat(body(MESSAGE_RFC822_DIGEST, 1, 2)).isEqualTo("From: m2@example.com\r\nSubject: m2\r\n\r\nm2 body\r\n");
    }

    @Test
    void aDigestPartWithoutContentTypeShouldBeAMessage() throws Exception {
        MimePart part = locate(MESSAGE_RFC822_DIGEST, 1, 1);

        assertThat(part.mimeHeaders()).isEmpty();
        assertThat(part.messageHeaders()).extracting(Header::getValue).containsExactly("m1@example.com", "m1");
        assertThat(new String(part.messageBody(), US_ASCII)).isEqualTo("m1 body\r\n");
    }

    @Test
    void aDigestPartShouldKeepItsOwnMimeHeader() throws Exception {
        assertThat(locate(MESSAGE_RFC822_DIGEST, 1, 2).mimeHeaders()).extracting(Header::getName).containsExactly("X-Mime");
    }

    @Test
    void aTextPartShouldHaveNoMessageHeaderNorParts() throws Exception {
        String text = "Content-Type: text/plain\r\n\r\nbody\r\n";

        assertThat(locate(text, 1).messageHeaders()).isEmpty();
        assertThat(MimePart.message(text.getBytes(US_ASCII)).locate(new int[] {1, 1})).isEmpty();
    }

    private String body(String message, int... path) throws Exception {
        return new String(locate(message, path).body(), US_ASCII);
    }

    private MimePart locate(String message, int... path) throws Exception {
        return MimePart.message(message.getBytes(US_ASCII)).locate(path).orElseThrow();
    }
}
