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

package mx.agromarin.james.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.lib.DomainListConfiguration;
import org.apache.james.domainlist.memory.MemoryDomainList;
import org.apache.james.protocols.smtp.SMTPConfiguration;
import org.apache.james.protocols.smtp.SMTPConfigurationImpl;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.protocols.smtp.utils.BaseFakeSMTPSession;
import org.apache.james.rrt.api.RecipientRewriteTableConfiguration;
import org.apache.james.rrt.memory.MemoryRecipientRewriteTable;
import org.apache.james.user.memory.MemoryUsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OdooValidRcptHandlerTest {
    private static final Domain DOMAIN = Domain.of("agromarin.test");
    private static final MaybeSender SENDER = MaybeSender.getMailSender("zed@supplier.test");

    private Set<String> odoo = Set.of("ventas@agromarin.test", "catchall@agromarin.test", "alias@elsewhere.test");
    private SQLException failure;
    private OdooDirectory addresses;
    private MemoryUsersRepository users;
    private OdooValidRcptHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        MemoryDomainList domains = new MemoryDomainList(mock(DNSService.class));
        domains.configure(DomainListConfiguration.builder().defaultDomain(DOMAIN).build());
        users = MemoryUsersRepository.withVirtualHosting(domains);
        users.addUser(Username.of("maria@agromarin.test"), "secret");
        MemoryRecipientRewriteTable rrt = new MemoryRecipientRewriteTable();
        rrt.setDomainList(domains);
        rrt.setConfiguration(RecipientRewriteTableConfiguration.DEFAULT_ENABLED);
        addresses = new OdooDirectory(() -> {
            if (failure != null) {
                throw failure;
            }
            return new OdooDirectory.Contents(odoo, Map.of());
        }, Duration.ofSeconds(30), Clock.systemUTC());
        handler = new OdooValidRcptHandler(users, rrt, domains, addresses);
    }

    private HookResult rcpt(String address) throws Exception {
        return handler.doRcpt(session(), SENDER, new MailAddress(address));
    }

    @Test
    void anOdooAddressIsAccepted() throws Exception {
        addresses.refresh();

        assertThat(rcpt("ventas@agromarin.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    @Test
    void anOdooAddressIsAcceptedInAnyCase() throws Exception {
        addresses.refresh();

        assertThat(rcpt("Ventas@AgroMarin.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    @Test
    void aJamesUserIsStillAccepted() throws Exception {
        addresses.refresh();

        assertThat(rcpt("maria@agromarin.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    @Test
    void anAddressNeitherAnswersIsRefused() throws Exception {
        addresses.refresh();

        HookResult result = rcpt("nadie@agromarin.test");

        assertThat(result.getResult()).isEqualTo(HookReturnCode.deny());
        assertThat(result.getSmtpRetCode()).isEqualTo("550");
    }

    @Test
    void anAddressInBothSystemsIsDeferredAsAConfigurationError() throws Exception {
        odoo = Set.of("maria@agromarin.test");
        addresses.refresh();

        HookResult result = rcpt("maria@agromarin.test");

        assertThat(result.getResult()).isEqualTo(HookReturnCode.denySoft());
        assertThat(result.getSmtpDescription()).startsWith("4.3.5 ");
    }

    @Test
    void anAddressOdooDroppedIsRefusedAfterTheNextRefresh() throws Exception {
        addresses.refresh();
        odoo = Set.of();
        addresses.refresh();

        assertThat(rcpt("ventas@agromarin.test").getResult()).isEqualTo(HookReturnCode.deny());
    }

    @Test
    void beforeOdoosAddressesAreReadAnUnknownLocalAddressIsDeferred() throws Exception {
        HookResult result = rcpt("ventas@agromarin.test");

        assertThat(result.getResult()).isEqualTo(HookReturnCode.denySoft());
        assertThat(result.getSmtpDescription()).startsWith("4.4.3 ");
    }

    @Test
    void beforeOdoosAddressesAreReadAJamesUserIsAccepted() throws Exception {
        assertThat(rcpt("maria@agromarin.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    @Test
    void aFailedRefreshKeepsTheAddressesLastRead() throws Exception {
        addresses.refresh();
        odoo = Set.of();
        failure = new SQLException("connection refused");

        assertThat(addresses.refresh()).isFalse();
        assertThat(rcpt("ventas@agromarin.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    @Test
    void anOdooAddressOutsideJamesDomainsIsLeftToTheRelayRules() throws Exception {
        addresses.refresh();

        assertThat(rcpt("alias@elsewhere.test").getResult()).isEqualTo(HookReturnCode.declined());
        assertThat(rcpt("other@elsewhere.test").getResult()).isEqualTo(HookReturnCode.declined());
    }

    private static SMTPSession session() {
        return new BaseFakeSMTPSession() {
            private final HashMap<AttachmentKey<?>, Object> state = new HashMap<>();

            @Override
            public boolean isRelayingAllowed() {
                return false;
            }

            @Override
            public SMTPConfiguration getConfiguration() {
                return new SMTPConfigurationImpl();
            }

            @Override
            public <T> Optional<T> setAttachment(AttachmentKey<T> key, T value, State scope) {
                return key.convert(state.put(key, value));
            }

            @Override
            public <T> Optional<T> removeAttachment(AttachmentKey<T> key, State scope) {
                return key.convert(state.remove(key));
            }

            @Override
            public <T> Optional<T> getAttachment(AttachmentKey<T> key, State scope) {
                return key.convert(state.get(key));
            }
        };
    }
}
