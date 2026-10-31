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
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Optional;

import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.stream.EntityState;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.stream.MimeTokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;

/**
 * Finds a string in a message's decoded textual parts (text/*, and a multipart's preamble and epilogue), and in its
 * header field values when asked.
 *
 * Replaces mime4j's MessageMatcher, which reports a match only once the character after it is read, so a string ending
 * a part that has no trailing line break is never found, and which restarts a failed partial match from the failing
 * character alone, so it misses "abac" in "ababac". A non-textual part is not searched: its bytes, decoded as
 * characters, are noise.
 */
public class MessageTextMatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageTextMatcher.class);
    private static final MimeConfig MIME_CONFIG = MimeConfig.custom()
        .setMaxLineLen(-1)
        .setMaxHeaderLen(-1)
        .build();
    private static final int CHUNK_SIZE = 8192;

    public record Text(List<String> fieldBodies, List<String> bodies) {
    }

    public static boolean contains(InputStream message, String text, boolean includeHeaders) throws IOException, MimeException {
        String searched = upperCase(text);
        MimeTokenStream parser = new MimeTokenStream(MIME_CONFIG);
        parser.parse(message);
        for (EntityState state = parser.getState(); state != EntityState.T_END_OF_STREAM; state = parser.next()) {
            if (state == EntityState.T_FIELD && includeHeaders && isFoundIn(new StringReader(parser.getField().getBody()), searched)) {
                return true;
            }
            if (isSearchedText(parser, state)) {
                Optional<Reader> reader = reader(parser);
                if (reader.isPresent() && isFoundIn(reader.get(), searched)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Text text(InputStream message) throws IOException, MimeException {
        ImmutableList.Builder<String> fieldBodies = ImmutableList.builder();
        ImmutableList.Builder<String> bodies = ImmutableList.builder();
        MimeTokenStream parser = new MimeTokenStream(MIME_CONFIG);
        parser.parse(message);
        for (EntityState state = parser.getState(); state != EntityState.T_END_OF_STREAM; state = parser.next()) {
            if (state == EntityState.T_FIELD) {
                fieldBodies.add(parser.getField().getBody());
            }
            if (isSearchedText(parser, state)) {
                Optional<Reader> reader = reader(parser);
                if (reader.isPresent()) {
                    bodies.add(CharStreams.toString(reader.get()));
                }
            }
        }
        return new Text(fieldBodies.build(), bodies.build());
    }

    private static boolean isSearchedText(MimeTokenStream parser, EntityState state) {
        return switch (state) {
            case T_PREAMBLE, T_EPILOGUE -> true;
            case T_BODY -> parser.getBodyDescriptor().getMimeType().startsWith("text/");
            default -> false;
        };
    }

    private static Optional<Reader> reader(MimeTokenStream parser) {
        try {
            return Optional.of(parser.getReader());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException | UnsupportedEncodingException e) {
            LOGGER.debug("Skipping a part of unknown charset", e);
            return Optional.empty();
        }
    }

    static boolean isFoundIn(Reader reader, String searched) throws IOException {
        if (searched.isEmpty()) {
            return true;
        }
        char[] chunk = new char[CHUNK_SIZE];
        StringBuilder window = new StringBuilder();
        int read;
        while ((read = reader.read(chunk)) != -1) {
            for (int i = 0; i < read; i++) {
                window.append(Character.toUpperCase(chunk[i]));
            }
            if (window.indexOf(searched) >= 0) {
                return true;
            }
            window.delete(0, Math.max(0, window.length() - searched.length() + 1));
        }
        return false;
    }

    public static String upperCase(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.chars().forEach(c -> result.append(Character.toUpperCase((char) c)));
        return result.toString();
    }
}
