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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.apache.james.core.MailAddress;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.core.healthcheck.ResultStatus;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.mailet.Mail;
import org.apache.mailet.base.test.FakeMail;
import org.apache.mailet.base.test.FakeMatcherConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class OdooAddressesTest {
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final UpdatableTickingClock clock = new UpdatableTickingClock(Instant.parse("2027-01-04T15:00:00Z"));
    private SQLException failure;
    private OdooAddresses addresses;

    @BeforeEach
    void setUp() {
        addresses = new OdooAddresses(() -> {
            if (failure != null) {
                throw failure;
            }
            return Set.of("ventas@agromarin.test");
        }, INTERVAL, clock);
    }

    private ResultStatus health() {
        return Mono.from(new OdooAddressesHealthCheck(addresses, clock).check()).block().getStatus();
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
        OdooAddressesUnknown matcher = new OdooAddressesUnknown(addresses);
        matcher.init(FakeMatcherConfig.builder().matcherName("OdooAddressesUnknown").build());

        assertThat(matcher.match(mail())).hasSize(2);
        addresses.refresh();
        assertThat(matcher.match(mail())).isEmpty();
    }
}
