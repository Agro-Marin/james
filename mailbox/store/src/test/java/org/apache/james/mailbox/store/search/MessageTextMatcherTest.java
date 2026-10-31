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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MessageTextMatcherTest {
    private static boolean bodyContains(String message, String text) throws Exception {
        return MessageTextMatcher.contains(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)), text, false);
    }

    private static boolean contains(String message, String text) throws Exception {
        return MessageTextMatcher.contains(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)), text, true);
    }

    @Test
    void shouldFindTextEndingABodyWithoutLineBreak() throws Exception {
        assertThat(bodyContains("Subject: s\r\n\r\ntestmail", "testmail")).isTrue();
    }

    @Test
    void shouldFindTextEndingAPartBeforeItsBoundary() throws Exception {
        String message = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\nContent-Type: text/plain\r\n\r\nfirst part\r\n"
            + "--b\r\nContent-Type: text/plain\r\n\r\nsecond part\r\n"
            + "--b--\r\n";

        assertThat(bodyContains(message, "first part")).isTrue();
    }

    @Test
    void shouldFindTextOverlappingAFailedPartialMatch() throws Exception {
        assertThat(bodyContains("Subject: s\r\n\r\nababac\r\n", "abac")).isTrue();
    }

    @Test
    void shouldFindTextAcrossReadChunks() throws Exception {
        String body = "x".repeat(8190) + "needle" + "y".repeat(100);

        assertThat(bodyContains("Subject: s\r\n\r\n" + body, "needle")).isTrue();
    }

    @Test
    void shouldIgnoreCase() throws Exception {
        assertThat(bodyContains("Subject: s\r\n\r\nHello World\r\n", "hELLO wORLD")).isTrue();
    }

    @Test
    void shouldSearchDecodedBody() throws Exception {
        String message = "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + "dGVzdG1haWw=\r\n";

        assertThat(bodyContains(message, "testmail")).isTrue();
    }

    @Test
    void shouldNotSearchANonTextualPart() throws Exception {
        String message = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\nContent-Type: text/plain\r\n\r\nbody\r\n"
            + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + "bmVlZGxl\r\n"
            + "--b--\r\n";

        assertThat(bodyContains(message, "needle")).isFalse();
    }

    @Test
    void shouldSearchATextualAttachment() throws Exception {
        String message = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\nContent-Type: text/plain\r\n\r\nbody\r\n"
            + "--b\r\nContent-Type: text/csv\r\nContent-Disposition: attachment; filename=a.csv\r\n\r\n"
            + "needle,1\r\n"
            + "--b--\r\n";

        assertThat(bodyContains(message, "needle")).isTrue();
    }

    @Test
    void shouldSearchABodyWithoutContentType() throws Exception {
        assertThat(bodyContains("Subject: s\r\n\r\nneedle\r\n", "needle")).isTrue();
    }

    @Test
    void textShouldBeTheSearchedText() throws Exception {
        String message = "Subject: =?UTF-8?Q?caf=C3=A9?=\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + "dGVzdG1haWw=\r\n"
            + "--b\r\nContent-Type: application/octet-stream\r\n\r\nbinary\r\n"
            + "--b--\r\n";

        MessageTextMatcher.Text text = MessageTextMatcher.text(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)));

        assertThat(text.bodies()).containsExactly("testmail");
        assertThat(text.fieldBodies()).contains("=?UTF-8?Q?caf=C3=A9?=", "multipart/mixed; boundary=b", "text/plain; charset=UTF-8");
    }

    @Test
    void shouldNotFindAbsentText() throws Exception {
        assertThat(bodyContains("Subject: s\r\n\r\ntestmai", "testmail")).isFalse();
    }

    @Test
    void bodySearchShouldIgnoreHeaders() throws Exception {
        assertThat(bodyContains("Subject: needle\r\n\r\nbody\r\n", "needle")).isFalse();
    }

    @Test
    void searchShouldFindHeaderValuesWhenIncludingHeaders() throws Exception {
        assertThat(contains("Subject: needle\r\n\r\nbody\r\n", "needle")).isTrue();
    }

    @Test
    void searchShouldNotFindHeaderNames() throws Exception {
        assertThat(contains("Subject: s\r\n\r\nbody\r\n", "subject")).isFalse();
    }

    @Test
    void emptyTextShouldBeFoundAnywhere() throws Exception {
        assertThat(MessageTextMatcher.isFoundIn(new StringReader(""), "")).isTrue();
    }
}
