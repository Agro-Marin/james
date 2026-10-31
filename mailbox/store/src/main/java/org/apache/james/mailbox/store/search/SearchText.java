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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.james.mailbox.AttachmentContentLoader;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.model.MessageAttachmentMetadata;
import org.apache.james.mailbox.model.SearchQuery.AddressType;
import org.apache.james.mailbox.store.ResultUtils;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.dom.address.Group;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.field.address.LenientAddressParser;
import org.apache.james.mime4j.util.MimeUtil;

/**
 * What an index keeps of a message so that its candidates for a text criterion hold every message
 * {@link MessageSearches} matches: each string the matcher compares, in the case the matcher compares it in, one a line.
 * A criterion's needle, in the same case, can only be found in a column holding a string it is found in.
 */
public record SearchText(String headers, String bodies, String attachments, String attachmentNames) {
    private static final String SEPARATOR = "\n";

    public static SearchText of(MailboxMessage message, TextExtractor textExtractor, AttachmentContentLoader attachmentContentLoader,
                                MailboxSession session) throws IOException, MimeException {
        MessageTextMatcher.Text text;
        try (InputStream content = message.getFullContent()) {
            text = MessageTextMatcher.text(content);
        }
        return new SearchText(
            lines(Stream.concat(
                text.fieldBodies().stream().map(MessageTextMatcher::upperCase),
                ResultUtils.createHeaders(message).stream().flatMap(SearchText::headerStrings))),
            lines(text.bodies().stream().map(MessageTextMatcher::upperCase)),
            lines(message.getAttachments().stream()
                .flatMap(attachment -> MessageSearches.attachmentText(attachment.getAttachment(), session, attachmentContentLoader, textExtractor))),
            lines(message.getAttachments().stream()
                .map(MessageAttachmentMetadata::getName)
                .flatMap(Optional::stream)));
    }

    public static String headerNeedle(String value) {
        return value.toUpperCase(Locale.US);
    }

    public static String textNeedle(String value) {
        return MessageTextMatcher.upperCase(value);
    }

    private static Stream<String> headerStrings(Header header) {
        String value = header.getValue();
        Stream<String> values = Stream.of(value, MimeUtil.unscrambleHeaderValue(value));
        if (isAddressHeader(header.getName())) {
            values = Stream.concat(values, LenientAddressParser.DEFAULT.parseAddressList(value).stream()
                .flatMap(address -> address instanceof Group group ? group.getMailboxes().stream() : Stream.of((Mailbox) address))
                .map(MessageSearches::encodeAndUnscramble));
        }
        return values.map(SearchText::headerNeedle);
    }

    private static boolean isAddressHeader(String name) {
        return Arrays.stream(AddressType.values()).anyMatch(type -> type.name().equalsIgnoreCase(name));
    }

    private static String lines(Stream<String> strings) {
        // PostgreSQL's text refuses NUL, which no needle holds
        return strings.map(string -> string.replace('\0', ' '))
            .collect(Collectors.joining(SEPARATOR));
    }
}
