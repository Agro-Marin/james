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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.apache.james.core.MailAddress;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

/**
 * The addresses Odoo answers, read from its {@code mail_gateway_recipient} view and refreshed on an interval. A
 * failed refresh keeps the last addresses read, so Odoo's database restarting refuses nothing; until the first read
 * succeeds, every lookup is {@link Lookup#UNKNOWN}.
 */
public class OdooAddresses implements Startable {
    public enum Lookup { ODOO, NOT_ODOO, UNKNOWN }

    @FunctionalInterface
    public interface Source {
        Set<String> load() throws SQLException;
    }

    public record Snapshot(Set<String> addresses, Instant loadedAt) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(OdooAddresses.class);
    private static final int TIMEOUT_SECONDS = 10;

    private final Source source;
    private final Duration refreshInterval;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private ScheduledExecutorService scheduler;

    public OdooAddresses(Source source, Duration refreshInterval, Clock clock) {
        this.source = source;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    public static OdooAddresses of(OdooConfiguration configuration) {
        return new OdooAddresses(database(configuration), configuration.refreshInterval(), Clock.systemUTC());
    }

    static Source database(OdooConfiguration configuration) {
        return () -> {
            Properties properties = new Properties();
            properties.setProperty("user", configuration.databaseUser());
            configuration.databasePassword().ifPresent(password -> properties.setProperty("password", password));
            properties.setProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS));
            properties.setProperty("socketTimeout", String.valueOf(3 * TIMEOUT_SECONDS));
            properties.setProperty("ApplicationName", "james-odoo-addresses");
            try (Connection connection = new org.postgresql.Driver().connect(configuration.databaseUrl(), properties)) {
                if (connection == null) {
                    throw new SQLException("not a PostgreSQL URL: " + configuration.databaseUrl());
                }
                connection.setReadOnly(true);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(TIMEOUT_SECONDS);
                    try (ResultSet rows = statement.executeQuery("SELECT address FROM mail_gateway_recipient")) {
                        ImmutableSet.Builder<String> addresses = ImmutableSet.builder();
                        while (rows.next()) {
                            Optional.ofNullable(rows.getString(1)).map(OdooAddresses::normalize).ifPresent(addresses::add);
                        }
                        return addresses.build();
                    }
                }
            }
        };
    }

    private static String normalize(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    public Lookup lookup(MailAddress address) {
        Snapshot current = snapshot.get();
        if (current == null) {
            return Lookup.UNKNOWN;
        }
        return current.addresses().contains(normalize(address.asString())) ? Lookup.ODOO : Lookup.NOT_ODOO;
    }

    public Optional<Snapshot> snapshot() {
        return Optional.ofNullable(snapshot.get());
    }

    public Duration refreshInterval() {
        return refreshInterval;
    }

    public boolean refresh() {
        try {
            Set<String> addresses = source.load();
            Snapshot previous = snapshot.getAndSet(new Snapshot(addresses, clock.instant()));
            if (previous == null || !previous.addresses().equals(addresses)) {
                LOGGER.info("Odoo answers {} addresses", addresses.size());
            }
            return true;
        } catch (Exception error) {
            LOGGER.warn("Cannot read Odoo's addresses, keeping the {}", snapshot()
                .map(current -> current.addresses().size() + " read at " + current.loadedAt())
                .orElse("none read so far"), error);
            return false;
        }
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "odoo-addresses");
            thread.setDaemon(true);
            return thread;
        });
        refresh();
        scheduler.scheduleWithFixedDelay(this::refresh, refreshInterval.toMillis(), refreshInterval.toMillis(),
            TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
