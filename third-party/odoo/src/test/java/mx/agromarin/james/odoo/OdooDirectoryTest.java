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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.core.healthcheck.ResultStatus;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.mailet.Mail;
import org.apache.mailet.base.test.FakeMail;
import org.apache.mailet.base.test.FakeMatcherConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class OdooDirectoryTest {
    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final String MARIA = "maria@agromarin.test";
    private static final String KEY = "25229a6777bdf7b70698d90ff4d6f0d5632f6445";
    private static final OdooDirectory.MailKey MARIA_KEY = new OdooDirectory.MailKey("25229a67",
        "$pbkdf2-sha512$6000$mcjzpgbtZP1w5ZbK16qX.g$oDNFmShW1I62bCy7.0ULZJLoAlPT/Xsz4gBWSvk3I0C1oijqgMaOvIwP91yuQiiQeHdLMl8ZW0sROn1X0IlN5Q",
        Optional.of(Instant.parse("2027-06-01T00:00:00Z")));

    private final UpdatableTickingClock clock = new UpdatableTickingClock(Instant.parse("2027-01-04T15:00:00Z"));
    private SQLException failure;
    private OdooDirectory addresses;

    @BeforeEach
    void setUp() {
        addresses = new OdooDirectory(() -> {
            if (failure != null) {
                throw failure;
            }
            return new OdooDirectory.Contents(Set.of("ventas@agromarin.test"), Map.of(MARIA, List.of(MARIA_KEY)));
        }, INTERVAL, clock);
    }

    private ResultStatus health() {
        return Mono.from(new OdooDirectoryHealthCheck(addresses, clock).check()).block().getStatus();
    }

    private static Mail mail() throws Exception {
        return FakeMail.builder()
            .name("m1")
            .sender("zed@supplier.test")
            .recipients("maria@agromarin.test", "ventas@agromarin.test")
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder().setSubject("Pedido").setText("Hola"))
            .build();
    }

    @Test
    void neverReadIsUnhealthy() {
        assertThat(health()).isEqualTo(ResultStatus.UNHEALTHY);
    }

    @Test
    void readWithinThreeIntervalsIsHealthy() {
        addresses.refresh();
        clock.setInstant(clock.instant().plus(INTERVAL.multipliedBy(3)));

        assertThat(health()).isEqualTo(ResultStatus.HEALTHY);
    }

    @Test
    void readLongerAgoIsDegraded() {
        addresses.refresh();
        failure = new SQLException("connection refused");
        clock.setInstant(clock.instant().plus(INTERVAL.multipliedBy(3)).plusSeconds(1));
        addresses.refresh();

        assertThat(health()).isEqualTo(ResultStatus.DEGRADED);
    }

    @Test
    void recipientIsOdooMatchesOnlyOdoosAddresses() throws Exception {
        addresses.refresh();
        RecipientIsOdoo matcher = new RecipientIsOdoo(addresses);
        matcher.init(FakeMatcherConfig.builder().matcherName("RecipientIsOdoo").build());

        assertThat(matcher.match(mail())).containsExactly(new MailAddress("ventas@agromarin.test"));
    }

    @Test
    void odooAddressesUnknownHoldsEveryRecipientUntilTheFirstRead() throws Exception {
        OdooDirectoryUnknown matcher = new OdooDirectoryUnknown(addresses);
        matcher.init(FakeMatcherConfig.builder().matcherName("OdooDirectoryUnknown").build());

        assertThat(matcher.match(mail())).hasSize(2);
        addresses.refresh();
        assertThat(matcher.match(mail())).isEmpty();
    }

    @Test
    void aKeyOdooHashedOpensItsMailboxAndNothingElseDoes() {
        assertThat(MARIA_KEY.opens(KEY, clock.instant())).isTrue();
        assertThat(MARIA_KEY.opens(KEY.replace('5', '6'), clock.instant())).isFalse();
        assertThat(MARIA_KEY.opens("25229a67", clock.instant())).isFalse();
        assertThat(MARIA_KEY.opens(null, clock.instant())).isFalse();
    }

    @Test
    void anExpiredKeyOpensNothing() {
        assertThat(MARIA_KEY.opens(KEY, Instant.parse("2027-06-01T00:00:01Z"))).isFalse();
    }

    @Test
    void odoosMailboxesAreJamesUsersSignedInWithTheirMailKeys() throws Exception {
        OdooUsersDAO users = new OdooUsersDAO(addresses);
        assertThat(users.contains(Username.of(MARIA))).isFalse();
        addresses.refresh();

        assertThat(users.contains(Username.of("Maria@AgroMarin.test"))).isTrue();
        assertThat(users.countUsers()).isEqualTo(1);
        assertThat(users.getUserByName(Username.of(MARIA))).hasValueSatisfying(user -> {
            assertThat(user.verifyPassword(KEY)).isTrue();
            assertThat(user.verifyPassword("odoo-password")).isFalse();
        });
        assertThat(users.getUserByName(Username.of("ventas@agromarin.test"))).isEmpty();
        assertThatThrownBy(() -> users.addUser(Username.of("nuevo@agromarin.test"), "x"))
            .isInstanceOf(UsersRepositoryException.class);
    }

    @Test
    void aFailedRefreshKeepsTheMailboxesSignedIn() throws Exception {
        addresses.refresh();
        failure = new SQLException("connection refused");
        addresses.refresh();

        assertThat(new OdooUsersDAO(addresses).getUserByName(Username.of(MARIA)))
            .hasValueSatisfying(user -> assertThat(user.verifyPassword(KEY)).isTrue());
    }
}
