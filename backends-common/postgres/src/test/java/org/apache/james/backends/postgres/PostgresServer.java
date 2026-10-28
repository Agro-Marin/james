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

package org.apache.james.backends.postgres;

import org.apache.james.backends.postgres.PostgresFixture.Database;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.Result;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PostgresServer {
    String EXTERNAL_PROPERTY = "james.postgres.external";

    static PostgresServer get() {
        return Holder.INSTANCE;
    }

    String host();

    int port();

    Database scoped(Database database);

    void start();

    void pause();

    void unpause();

    void restart();

    default void execute(Database credentials, String databaseName, String sql) {
        Flux.usingWhen(connectionFactory(credentials, databaseName).create(),
                connection -> Flux.from(connection.createStatement(sql).execute()).flatMap(Result::getRowsUpdated),
                connection -> connection.close())
            .blockLast();
    }

    default boolean exists(Database credentials, String databaseName, String sql) {
        return Boolean.TRUE.equals(Mono.usingWhen(connectionFactory(credentials, databaseName).create(),
                connection -> Mono.from(connection.createStatement(sql).execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get(0, Boolean.class)))),
                connection -> connection.close())
            .block());
    }

    private PostgresqlConnectionFactory connectionFactory(Database credentials, String databaseName) {
        return new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
            .host(host())
            .port(port())
            .database(databaseName)
            .username(credentials.dbUser())
            .password(credentials.dbPassword())
            .build());
    }

    private static PostgresServer select() {
        String external = System.getProperty(EXTERNAL_PROPERTY);
        if (external == null || external.isBlank()) {
            return new DockerPostgresServer();
        }
        return ExternalPostgresServer.parse(external);
    }

    final class Holder {
        private static final PostgresServer INSTANCE = select();
    }
}
