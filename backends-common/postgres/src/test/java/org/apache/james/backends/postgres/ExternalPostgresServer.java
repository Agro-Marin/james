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

import static org.apache.james.backends.postgres.PostgresFixture.Database.DEFAULT_DATABASE;

import org.apache.james.backends.postgres.PostgresFixture.Database;
import org.junit.jupiter.api.Assumptions;

import com.google.common.base.Preconditions;

/**
 * A PostgreSQL server the tests do not own, named by {@code -Djames.postgres.external=host:port}. It must have the
 * superuser of {@link Database#DEFAULT_DATABASE}. Each JVM works in databases suffixed with its pid, as each JVM gets
 * its own container otherwise; nothing drops them, so point it at a throwaway server.
 */
class ExternalPostgresServer implements PostgresServer {
    static ExternalPostgresServer parse(String hostAndPort) {
        int separator = hostAndPort.lastIndexOf(':');
        Preconditions.checkArgument(separator > 0, "%s must be host:port, got '%s'", EXTERNAL_PROPERTY, hostAndPort);
        return new ExternalPostgresServer(hostAndPort.substring(0, separator), Integer.parseInt(hostAndPort.substring(separator + 1)));
    }

    private final String host;
    private final int port;
    private final String suffix = "_t" + ProcessHandle.current().pid();
    private boolean started;

    private ExternalPostgresServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public String host() {
        return host;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public Database scoped(Database database) {
        String name = database.dbName() + suffix;
        return new Database() {
            @Override
            public String dbUser() {
                return database.dbUser();
            }

            @Override
            public String dbPassword() {
                return database.dbPassword();
            }

            @Override
            public String dbName() {
                return name;
            }

            @Override
            public String schema() {
                return database.schema();
            }
        };
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        String name = scoped(DEFAULT_DATABASE).dbName();
        if (!exists(DEFAULT_DATABASE, "postgres", "SELECT EXISTS (SELECT FROM pg_database WHERE datname = '" + name + "')")) {
            execute(DEFAULT_DATABASE, "postgres", "CREATE DATABASE " + name + " OWNER " + DEFAULT_DATABASE.dbUser());
        }
        started = true;
    }

    @Override
    public void pause() {
        abortWithoutContainer("pause");
    }

    @Override
    public void unpause() {
        abortWithoutContainer("unpause");
    }

    @Override
    public void restart() {
        abortWithoutContainer("restart");
    }

    private void abortWithoutContainer(String operation) {
        Assumptions.abort(operation + " needs the Docker container, and " + EXTERNAL_PROPERTY + " is set");
    }
}
