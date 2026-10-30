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

package org.apache.james.mailbox.store.streaming;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.EntityState;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.stream.MimeTokenStream;
import org.apache.james.mime4j.stream.RecursionMode;

import com.google.common.collect.ImmutableList;

/**
 * A part of a message, located by its IMAP part number (RFC 3501 6.4.5) in the message's raw bytes: its MIME header
 * fields and its raw body. A multipart's parts are its body parts; a message/rfc822 part's parts are those of the
 * message it holds; a message that is not multipart has a single part 1, its body.
 */
public final class MimePart {
    private static final String TEXT_PLAIN = "text/plain";
    private static final String MESSAGE_RFC822 = "message/rfc822";

    public static MimePart message(byte[] raw) throws IOException, MimeException {
        return parse(raw, TEXT_PLAIN);
    }

    private static MimePart parse(byte[] raw, String defaultMimeType) throws IOException, MimeException {
        MimeTokenStream parser = new MimeTokenStream(MimeConfig.PERMISSIVE);
        parser.setRecursionMode(RecursionMode.M_FLAT);
        parser.parse(new ByteArrayInputStream(raw));
        ImmutableList.Builder<Header> headers = ImmutableList.builder();
        boolean hasContentType = false;
        for (EntityState state = parser.getState(); state != EntityState.T_END_OF_STREAM; state = parser.next()) {
            if (state == EntityState.T_FIELD) {
                String name = parser.getField().getName();
                hasContentType |= name.equalsIgnoreCase("Content-Type");
                headers.add(new Header(name, parser.getField().getBody().trim()));
            } else if (state == EntityState.T_BODY) {
                BodyDescriptor descriptor = parser.getBodyDescriptor();
                String mimeType = hasContentType ? descriptor.getMimeType().toLowerCase(Locale.US) : defaultMimeType;
                return new MimePart(headers.build(), mimeType, descriptor.getBoundary(), IOUtils.toByteArray(parser.getInputStream()));
            }
        }
        return new MimePart(headers.build(), defaultMimeType, null, new byte[0]);
    }

    private final List<Header> mimeHeaders;
    private final String mimeType;
    private final String boundary;
    private final byte[] body;

    private MimePart(List<Header> mimeHeaders, String mimeType, String boundary, byte[] body) {
        this.mimeHeaders = mimeHeaders;
        this.mimeType = mimeType;
        this.boundary = boundary;
        this.body = body;
    }

    /**
     * The part of this message at the given part number, empty when there is none.
     */
    public Optional<MimePart> locate(int[] path) throws IOException, MimeException {
        List<MimePart> candidates = isMultipart() ? bodyParts() : List.of(this);
        MimePart current = null;
        for (int position : path) {
            if (current != null) {
                candidates = current.parts();
            }
            if (position < 1 || position > candidates.size()) {
                return Optional.empty();
            }
            current = candidates.get(position - 1);
        }
        return Optional.ofNullable(current);
    }

    public List<Header> mimeHeaders() {
        return mimeHeaders;
    }

    public byte[] body() {
        return body;
    }

    /**
     * The header of the message a message/rfc822 part holds, for its HEADER section; none for another part.
     */
    public List<Header> messageHeaders() throws IOException, MimeException {
        return encapsulated().map(MimePart::mimeHeaders).orElse(List.of());
    }

    /**
     * The body of the message a message/rfc822 part holds, for its TEXT section; none for another part.
     */
    public byte[] messageBody() throws IOException, MimeException {
        return encapsulated().map(MimePart::body).orElse(new byte[0]);
    }

    private Optional<MimePart> encapsulated() throws IOException, MimeException {
        if (isMessage()) {
            return Optional.of(message(body));
        }
        return Optional.empty();
    }

    private List<MimePart> parts() throws IOException, MimeException {
        if (isMultipart()) {
            return bodyParts();
        }
        Optional<MimePart> message = encapsulated();
        if (message.isPresent()) {
            return message.get().isMultipart() ? message.get().bodyParts() : List.of(message.get());
        }
        return List.of();
    }

    private boolean isMultipart() {
        return mimeType.startsWith("multipart/") && boundary != null;
    }

    private boolean isMessage() {
        return mimeType.equals(MESSAGE_RFC822) || mimeType.equals("message/global");
    }

    // RFC 2046 5.1.1: a delimiter is "--" boundary at the start of a line, "--" more for the last one, then optional
    // whitespace; the line break before it belongs to the delimiter, not to the part. RFC 2046 5.1.5: in a digest a
    // body part without a Content-Type is a message/rfc822.
    private List<MimePart> bodyParts() throws IOException, MimeException {
        String defaultMimeType = mimeType.equals("multipart/digest") ? MESSAGE_RFC822 : TEXT_PLAIN;
        byte[] delimiter = ("--" + boundary).getBytes(US_ASCII);
        List<MimePart> parts = new ArrayList<>();
        int partStart = -1;
        int lineStart = 0;
        while (lineStart < body.length) {
            int lineFeed = indexOf((byte) '\n', lineStart);
            int lineEnd = lineFeed < 0 ? body.length : lineFeed;
            int nextLine = lineFeed < 0 ? body.length : lineFeed + 1;
            if (startsWith(delimiter, lineStart)) {
                int afterBoundary = lineStart + delimiter.length;
                boolean last = afterBoundary + 1 < lineEnd && body[afterBoundary] == '-' && body[afterBoundary + 1] == '-';
                if (isWhitespace(last ? afterBoundary + 2 : afterBoundary, lineEnd)) {
                    if (partStart >= 0) {
                        parts.add(parse(Arrays.copyOfRange(body, partStart, withoutLineBreakBefore(partStart, lineStart)), defaultMimeType));
                    }
                    if (last) {
                        return parts;
                    }
                    partStart = nextLine;
                }
            }
            lineStart = nextLine;
        }
        if (partStart >= 0) {
            parts.add(parse(Arrays.copyOfRange(body, partStart, body.length), defaultMimeType));
        }
        return parts;
    }

    private int withoutLineBreakBefore(int partStart, int delimiterStart) {
        int end = delimiterStart;
        if (end > partStart && body[end - 1] == '\n') {
            end--;
        }
        if (end > partStart && body[end - 1] == '\r') {
            end--;
        }
        return end;
    }

    private int indexOf(byte value, int from) {
        for (int i = from; i < body.length; i++) {
            if (body[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private boolean startsWith(byte[] prefix, int from) {
        if (from + prefix.length > body.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[from + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWhitespace(int from, int to) {
        for (int i = from; i < to; i++) {
            if (body[i] != ' ' && body[i] != '\t' && body[i] != '\r') {
                return false;
            }
        }
        return true;
    }
}
