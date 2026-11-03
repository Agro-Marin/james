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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import jakarta.annotation.PreDestroy;

import org.apache.james.core.MailAddress;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * What Odoo's database says about mail, refreshed on an interval: the addresses Odoo answers
 * ({@code mail_gateway_recipient}) and, when James's users are Odoo's, the mailboxes and their mail keys
 * ({@code mail_mailbox}, {@code mail_mailbox_key}). A failed refresh keeps the last read, so Odoo's database
 * restarting refuses nothing and locks nobody out; until the first read succeeds, every lookup is
 * {@link Lookup#UNKNOWN} and nobody signs in.
 */
public class OdooDirectory implements Startable {
    public enum Lookup { ODOO, NOT_ODOO, UNKNOWN }

    public record MailKey(String index, String encoded, Optional<Instant> expiration) {
        private static final Pattern PBKDF2_SHA512 = Pattern.compile("^\\$pbkdf2-sha512\\$(\\d+)\\$([^$]+)\\$([^$]+)$");
        private static final int INDEX_SIZE = 8;

        public boolean opens(String key, Instant now) {
            if (key == null || key.length() <= INDEX_SIZE || !key.startsWith(index)
                || expiration.filter(now::isAfter).isPresent()) {
                return false;
            }
            Matcher hash = PBKDF2_SHA512.matcher(encoded);
            if (!hash.matches()) {
                return false;
            }
            try {
                byte[] checksum = ab64(hash.group(3));
                PBEKeySpec spec = new PBEKeySpec(key.toCharArray(), ab64(hash.group(2)),
                    Integer.parseInt(hash.group(1)), checksum.length * Byte.SIZE);
                byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
                return MessageDigest.isEqual(derived, checksum);
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                return false;
            }
        }

        private static byte[] ab64(String value) {
            return Base64.getDecoder().decode(value.replace('.', '+').getBytes(StandardCharsets.US_ASCII));
        }
    }

    public record Contents(Set<String> addresses, Map<String, List<MailKey>> mailboxes) {
    }

    @FunctionalInterface
    public interface Source {
        Contents load() throws SQLException;
    }

    public record Snapshot(Contents contents, Instant loadedAt) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(OdooDirectory.class);
    private static final int TIMEOUT_SECONDS = 10;

    private final Source source;
    private final Duration refreshInterval;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private ScheduledExecutorService scheduler;

    public OdooDirectory(Source source, Duration refreshInterval, Clock clock) {
        this.source = source;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    public static OdooDirectory of(OdooConfiguration configuration) {
        return new OdooDirectory(database(configuration), configuration.refreshInterval(), Clock.systemUTC());
    }

    static Source database(OdooConfiguration configuration) {
        return () -> {
            Properties properties = new Properties();
            properties.setProperty("user", configuration.databaseUser());
            configuration.databasePassword().ifPresent(password -> properties.setProperty("password", password));
            properties.setProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS));
            properties.setProperty("socketTimeout", String.valueOf(3 * TIMEOUT_SECONDS));
            properties.setProperty("ApplicationName", "james-odoo-directory");
            try (Connection connection = new org.postgresql.Driver().connect(configuration.databaseUrl(), properties)) {
                if (connection == null) {
                    throw new SQLException("not a PostgreSQL URL: " + configuration.databaseUrl());
                }
                connection.setReadOnly(true);
                Set<String> addresses = addresses(connection);
                Map<String, List<MailKey>> mailboxes = configuration.usersEnabled() ? mailboxes(connection) : Map.of();
                return new Contents(addresses, mailboxes);
            }
        };
    }

    private static Set<String> addresses(Connection connection) throws SQLException {
        try (Statement statement = query(connection);
             ResultSet rows = statement.executeQuery("SELECT address FROM mail_gateway_recipient")) {
            ImmutableSet.Builder<String> addresses = ImmutableSet.builder();
            while (rows.next()) {
                Optional.ofNullable(rows.getString(1)).map(OdooDirectory::normalize).ifPresent(addresses::add);
            }
            return addresses.build();
        }
    }

    private static Map<String, List<MailKey>> mailboxes(Connection connection) throws SQLException {
        Map<String, ImmutableList.Builder<MailKey>> keys = new LinkedHashMap<>();
        try (Statement statement = query(connection);
             ResultSet rows = statement.executeQuery("SELECT address FROM mail_mailbox")) {
            while (rows.next()) {
                keys.put(normalize(rows.getString(1)), ImmutableList.builder());
            }
        }
        try (Statement statement = query(connection);
             ResultSet rows = statement.executeQuery("SELECT address, index, key, expiration_date FROM mail_mailbox_key")) {
            while (rows.next()) {
                Optional<Instant> expiration = Optional.ofNullable(rows.getTimestamp(4))
                    .map(Timestamp::toLocalDateTime)
                    .map(time -> time.toInstant(ZoneOffset.UTC));
                ImmutableList.Builder<MailKey> mailbox = keys.get(normalize(rows.getString(1)));
                if (mailbox != null) {
                    mailbox.add(new MailKey(rows.getString(2), rows.getString(3), expiration));
                }
            }
        }
        ImmutableMap.Builder<String, List<MailKey>> mailboxes = ImmutableMap.builder();
        keys.forEach((address, builder) -> mailboxes.put(address, builder.build()));
        return mailboxes.build();
    }

    private static Statement query(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.setQueryTimeout(TIMEOUT_SECONDS);
        return statement;
    }

    static String normalize(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    public Lookup lookup(MailAddress address) {
        Snapshot current = snapshot.get();
        if (current == null) {
            return Lookup.UNKNOWN;
        }
        return current.contents().addresses().contains(normalize(address.asString())) ? Lookup.ODOO : Lookup.NOT_ODOO;
    }

    public Optional<List<MailKey>> mailbox(String address) {
        return snapshot().map(current -> current.contents().mailboxes().get(normalize(address)));
    }

    public Collection<String> mailboxes() {
        return snapshot().map(current -> current.contents().mailboxes().keySet()).orElse(Set.of());
    }

    public Optional<Snapshot> snapshot() {
        return Optional.ofNullable(snapshot.get());
    }

    public Duration refreshInterval() {
        return refreshInterval;
    }

    public Instant now() {
        return clock.instant();
    }

    public boolean refresh() {
        try {
            Contents contents = source.load();
            Snapshot previous = snapshot.getAndSet(new Snapshot(contents, clock.instant()));
            if (previous == null || !previous.contents().addresses().equals(contents.addresses())
                || !previous.contents().mailboxes().keySet().equals(contents.mailboxes().keySet())) {
                LOGGER.info("Odoo answers {} addresses and has {} mailboxes", contents.addresses().size(),
                    contents.mailboxes().size());
            }
            return true;
        } catch (Exception error) {
            LOGGER.warn("Cannot read Odoo's directory, keeping the {}", snapshot()
                .map(current -> "one read at " + current.loadedAt())
                .orElse("none: nothing was read so far"), error);
            return false;
        }
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "odoo-directory");
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
