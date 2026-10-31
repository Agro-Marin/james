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
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.stream.EntityState;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.stream.MimeTokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a string in a message's decoded text parts, and in its header field values when asked.
 *
 * Replaces mime4j's MessageMatcher, which reports a match only once the character after it is read, so a string ending
 * a part that has no trailing line break is never found, and which restarts a failed partial match from the failing
 * character alone, so it misses "abac" in "ababac".
 */
public class MessageTextMatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageTextMatcher.class);
    private static final MimeConfig MIME_CONFIG = MimeConfig.custom()
        .setMaxLineLen(-1)
        .setMaxHeaderLen(-1)
        .build();
    private static final int CHUNK_SIZE = 8192;

    public static boolean contains(InputStream message, String text, boolean includeHeaders) throws IOException, MimeException {
        String searched = upperCase(text);
        MimeTokenStream parser = new MimeTokenStream(MIME_CONFIG);
        parser.parse(message);
        for (EntityState state = parser.getState(); state != EntityState.T_END_OF_STREAM; state = parser.next()) {
            if (isFound(parser, state, searched, includeHeaders)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFound(MimeTokenStream parser, EntityState state, String searched, boolean includeHeaders) throws IOException {
        switch (state) {
            case T_PREAMBLE, T_EPILOGUE, T_BODY:
                try {
                    return isFoundIn(parser.getReader(), searched);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    LOGGER.debug("Skipping a part of unknown charset", e);
                    return false;
                }
            case T_FIELD:
                return includeHeaders && isFoundIn(new StringReader(parser.getField().getBody()), searched);
            default:
                return false;
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

    private static String upperCase(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.chars().forEach(c -> result.append(Character.toUpperCase((char) c)));
        return result.toString();
    }
}
