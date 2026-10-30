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

/**
 * 
 */
package org.apache.james.imap.processor.fetch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.MimeUtility;

import org.apache.james.imap.api.ImapConstants;
import org.apache.james.imap.message.response.FetchResponse;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.model.Headers;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.codec.EncoderUtil;
import org.apache.james.mime4j.dom.address.Address;
import org.apache.james.mime4j.dom.address.DomainList;
import org.apache.james.mime4j.dom.address.Group;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.field.address.LenientAddressParser;
import org.apache.james.mime4j.stream.ParserCursor;
import org.apache.james.mime4j.stream.RawFieldParser;
import org.apache.james.mime4j.util.ByteSequence;
import org.apache.james.mime4j.util.CharsetUtil;
import org.apache.james.mime4j.util.ContentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EnvelopeBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvelopeBuilder.class);
    private static final BitSet COMMA = RawFieldParser.INIT_BITSET(',');
    private static final BitSet COLON = RawFieldParser.INIT_BITSET(':');
    private static final BitSet COMMA_OR_SEMICOLON = RawFieldParser.INIT_BITSET(',', ';');

    public FetchResponse.Envelope buildEnvelope(Headers headers) throws MailboxException {
        final String date = headerValue(headers, ImapConstants.RFC822_DATE);
        final String subject = headerValue(headers, ImapConstants.RFC822_SUBJECT);
        final FetchResponse.Envelope.Address[] fromAddresses = buildAddresses(headers, ImapConstants.RFC822_FROM);
        final FetchResponse.Envelope.Address[] senderAddresses = buildAddresses(headers, ImapConstants.RFC822_SENDER, fromAddresses);
        final FetchResponse.Envelope.Address[] replyToAddresses = buildAddresses(headers, ImapConstants.RFC822_REPLY_TO, fromAddresses);
        final FetchResponse.Envelope.Address[] toAddresses = buildAddresses(headers, ImapConstants.RFC822_TO);
        final FetchResponse.Envelope.Address[] ccAddresses = buildAddresses(headers, ImapConstants.RFC822_CC);
        final FetchResponse.Envelope.Address[] bccAddresses = buildAddresses(headers, ImapConstants.RFC822_BCC);
        final String inReplyTo = headerValue(headers, ImapConstants.RFC822_IN_REPLY_TO);
        final String messageId = headerValue(headers, ImapConstants.RFC822_MESSAGE_ID);
        return new EnvelopeImpl(date, subject, fromAddresses, senderAddresses, replyToAddresses, toAddresses, ccAddresses, bccAddresses, inReplyTo, messageId);
    }

    private String headerValue(Headers message, String headerName) throws MailboxException {
        final Header header = MessageResultUtils.getMatching(headerName, message.headers());
        if (header == null) {
            return null;
        }
        final String value = header.getValue();
        if (value == null || "".equals(value)) {
            return null;
        }
        // ENVELOPE header values must be unfolded
        // See IMAP-269
        //
        //
        // IMAP-Servers are advised to also replace tabs with single spaces while doing the unfolding. This is what javamails
        // unfold does. mime4j's unfold does strictly follow the rfc and so preserve them
        //
        // See IMAP-327 and https://mailman2.u.washington.edu/mailman/htdig/imap-protocol/2010-July/001271.html
        return MimeUtility.unfold(value);
    }

    private FetchResponse.Envelope.Address[] buildAddresses(Headers message, String headerName, FetchResponse.Envelope.Address[] defaults) throws MailboxException {
        final FetchResponse.Envelope.Address[] addresses = buildAddresses(message, headerName);
        if (addresses == null) {
            return defaults;
        }
        return addresses;
    }

    /**
     * Try to parse the addresses out of the header. If its not possible because
     * of a {@link ParseException} a null value is returned
     *
     * @return addresses
     */
    private FetchResponse.Envelope.Address[] buildAddresses(Headers message, String headerName) throws MailboxException {
        final Header header = MessageResultUtils.getMatching(headerName, message.headers());
        if (header == null) {
            return null;
        }
        // We need to unfold the header line.
        // See https://issues.apache.org/jira/browse/IMAP-154
        //
        // IMAP-Servers are advised to also replace tabs with single spaces while doing the unfolding. This is what javamails
        // unfold does. mime4j's unfold does strictly follow the rfc and so preserve them
        //
        // See IMAP-327 and https://mailman2.u.washington.edu/mailman/htdig/imap-protocol/2010-July/001271.html
        String value = MimeUtility.unfold(header.getValue());
        if ("".equals(value.trim())) {
            return null;
        }
        ByteSequence raw = ContentUtil.encode(value);
        ParserCursor cursor = new ParserCursor(0, raw.length());
        final List<FetchResponse.Envelope.Address> addresses = new ArrayList<>();
        while (!cursor.atEnd()) {
            int start = cursor.getPos();
            if (raw.byteAt(start) == ',') {
                cursor.updatePos(start + 1);
                continue;
            }
            Address address = LenientAddressParser.DEFAULT.parseAddress(raw, cursor, COMMA);
            if (address instanceof Group group) {
                addAddresses(group, raw, new ParserCursor(start, cursor.getPos()), addresses);
            } else if (address instanceof Mailbox mailbox) {
                addresses.add(buildMailboxAddress(mailbox, raw, start, cursor.getPos()));
            } else if (address != null) {
                LOGGER.warn("Unknown address type {}", address.getClass());
            }
        }
        return addresses.toArray(FetchResponse.Envelope.Address[]::new);
    }

    private FetchResponse.Envelope.Address buildMailboxAddress(Mailbox mailbox, ByteSequence raw, int start, int end) {
        final String name = encodedMailboxName(mailbox, raw, start, end);
        final String domain = mailbox.getDomain();
        final DomainList route = mailbox.getRoute();
        final String atDomainList;
        if (route == null || route.size() == 0) {
            atDomainList = null;
        } else {
            atDomainList = route.toRouteString();
        }
        final String localPart = mailbox.getLocalPart();
        return buildMailboxAddress(name, atDomainList, localPart, domain);
    }

    private String encodedMailboxName(Mailbox mailbox, ByteSequence raw, int start, int end) {
        // Encode the mailbox name
        // See IMAP-266
        return Optional.ofNullable(mailbox.getName())
            .or(() -> addrSpecComment(raw, start, end).map(comment -> DecoderUtil.decodeEncodedWords(comment, DecodeMonitor.SILENT)))
            .map(EncoderUtil::encodeAddressDisplayName)
            .orElse(null);
    }

    // An old-style "user@domain (Real Name)" names its mailbox in the comment, which mime4j skips: as in Dovecot,
    // the last comment of a bare addr-spec is its name, and a name-addr's comments are not.
    private static Optional<String> addrSpecComment(ByteSequence raw, int start, int end) {
        Optional<String> comment = Optional.empty();
        boolean inAddrSpec = false;
        int pos = start;
        while (pos < end) {
            char current = (char) (raw.byteAt(pos) & 0xff);
            if (current == '(') {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                pos = readComment(raw, pos + 1, end, text);
                if (inAddrSpec) {
                    comment = Optional.of(text.toString(StandardCharsets.UTF_8)).filter(name -> !name.isEmpty());
                }
            } else if (current == '<') {
                return Optional.empty();
            } else if (current == '"' || current == '[') {
                inAddrSpec = true;
                pos = skipQuoted(raw, pos + 1, end, current == '"' ? '"' : ']');
            } else {
                inAddrSpec |= !CharsetUtil.isWhitespace(current);
                pos++;
            }
        }
        return comment;
    }

    private static int readComment(ByteSequence raw, int pos, int end, ByteArrayOutputStream text) {
        int level = 1;
        while (pos < end) {
            byte current = raw.byteAt(pos);
            if (current == '\\' && pos + 1 < end) {
                pos++;
                current = raw.byteAt(pos);
            } else if (current == '(') {
                level++;
            } else if (current == ')' && --level == 0) {
                return pos + 1;
            }
            text.write(current);
            pos++;
        }
        return pos;
    }

    private static int skipQuoted(ByteSequence raw, int pos, int end, char closing) {
        while (pos < end) {
            byte current = raw.byteAt(pos);
            if (current == '\\') {
                pos++;
            } else if (current == closing) {
                return pos + 1;
            }
            pos++;
        }
        return pos;
    }

    private void addAddresses(Group group, ByteSequence raw, ParserCursor cursor, List<FetchResponse.Envelope.Address> addresses) {
        addresses.add(startGroup(group.getName()));
        RawFieldParser.DEFAULT.parseValue(raw, cursor, COLON);
        cursor.updatePos(cursor.getPos() + 1);
        while (!cursor.atEnd() && raw.byteAt(cursor.getPos()) != ';') {
            int start = cursor.getPos();
            if (raw.byteAt(start) == ',') {
                cursor.updatePos(start + 1);
                continue;
            }
            Mailbox mailbox = LenientAddressParser.DEFAULT.parseMailbox(raw, cursor, COMMA_OR_SEMICOLON);
            if (mailbox != null) {
                addresses.add(buildMailboxAddress(mailbox, raw, start, cursor.getPos()));
            }
        }
        addresses.add(endGroup());
    }

    private FetchResponse.Envelope.Address startGroup(String groupName) {
        return new AddressImpl(null, null, groupName, null);
    }

    private FetchResponse.Envelope.Address endGroup() {
        return new AddressImpl(null, null, null, null);
    }

    private FetchResponse.Envelope.Address buildMailboxAddress(String name, String atDomainList, String mailbox, String domain) {
        return new AddressImpl(atDomainList, domain, mailbox, name);
    }
}