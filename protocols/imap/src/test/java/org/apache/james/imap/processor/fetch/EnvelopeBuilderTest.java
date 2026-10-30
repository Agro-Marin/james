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

package org.apache.james.imap.processor.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.james.imap.message.response.FetchResponse.Envelope.Address;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.model.Headers;
import org.junit.jupiter.api.Test;

class EnvelopeBuilderTest {
    private static Address[] to(String value) throws Exception {
        Headers headers = mock(Headers.class);
        when(headers.headers()).thenAnswer(invocation -> List.of(new Header("To", value)).iterator());
        return new EnvelopeBuilder().buildEnvelope(headers).getTo();
    }

    @Test
    void bareAddrSpecShouldBeNamedByItsComment() throws Exception {
        assertThat(to("user@domain (Real Name)"))
            .extracting(Address::getPersonalName, Address::getMailboxName, Address::getHostName)
            .containsExactly(tuple("Real Name", "user", "domain"));
    }

    @Test
    void nameAddrShouldNotBeNamedByItsComment() throws Exception {
        assertThat(to("<user@domain> (Not A Name)"))
            .extracting(Address::getPersonalName, Address::getMailboxName, Address::getHostName)
            .containsExactly(tuple(null, "user", "domain"));
    }

    @Test
    void escapedAndNestedParenthesesShouldStayInTheName() throws Exception {
        assertThat(to("user@domain (Real \\) (Nested) Name)"))
            .extracting(Address::getPersonalName)
            .containsExactly("\"Real ) (Nested) Name\"");
    }

    @Test
    void nonAsciiHeaderShouldParseToItsEnd() throws Exception {
        assertThat(to("Jöhn <john@domain>, mary@example.net"))
            .extracting(Address::getMailboxName, Address::getHostName)
            .containsExactly(tuple("john", "domain"), tuple("mary", "example.net"));
    }
}
