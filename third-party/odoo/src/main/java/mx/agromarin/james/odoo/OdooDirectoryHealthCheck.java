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

import java.time.Clock;
import java.time.Duration;

import jakarta.inject.Inject;

import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;

public class OdooDirectoryHealthCheck implements HealthCheck {
    private static final ComponentName COMPONENT_NAME = new ComponentName("Odoo directory");
    private static final int MISSED_REFRESHES = 3;

    private final OdooDirectory addresses;
    private final Clock clock;

    @Inject
    public OdooDirectoryHealthCheck(OdooDirectory addresses) {
        this(addresses, Clock.systemUTC());
    }

    OdooDirectoryHealthCheck(OdooDirectory addresses, Clock clock) {
        this.addresses = addresses;
        this.clock = clock;
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Publisher<Result> check() {
        return Mono.fromSupplier(() -> addresses.snapshot()
            .map(snapshot -> {
                Duration age = Duration.between(snapshot.loadedAt(), clock.instant());
                if (age.compareTo(addresses.refreshInterval().multipliedBy(MISSED_REFRESHES)) > 0) {
                    return Result.degraded(COMPONENT_NAME, "Odoo's addresses were last read " + age.toSeconds()
                        + " s ago, at " + snapshot.loadedAt());
                }
                return Result.healthy(COMPONENT_NAME);
            })
            .orElseGet(() -> Result.unhealthy(COMPONENT_NAME,
                "Odoo's addresses were never read: mail for them is deferred")));
    }
}
