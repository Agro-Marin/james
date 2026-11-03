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

import java.time.Duration;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.util.DurationParser;

public record OdooConfiguration(String databaseUrl, String databaseUser, Optional<String> databasePassword,
                                Duration refreshInterval, boolean usersEnabled) {
    public static final String CONFIGURATION_NAME = "odoo";
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);

    public static OdooConfiguration from(Configuration configuration) throws ConfigurationException {
        String url = required(configuration, "database.url");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new ConfigurationException("database.url must be a jdbc:postgresql: URL, got " + url);
        }
        Duration refresh = Optional.ofNullable(configuration.getString("refresh.interval", null))
            .map(DurationParser::parse)
            .orElse(DEFAULT_REFRESH_INTERVAL);
        if (refresh.isNegative() || refresh.isZero()) {
            throw new ConfigurationException("refresh.interval must be positive, got " + refresh);
        }
        return new OdooConfiguration(url, required(configuration, "database.user"),
            Optional.ofNullable(configuration.getString("database.password", null)), refresh,
            configuration.getBoolean("users.enabled", false));
    }

    private static String required(Configuration configuration, String key) throws ConfigurationException {
        String value = configuration.getString(key, null);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(CONFIGURATION_NAME + ".properties needs " + key);
        }
        return value.strip();
    }
}
