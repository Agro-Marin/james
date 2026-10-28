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
import org.testcontainers.containers.PostgreSQLContainer;

class DockerPostgresServer implements PostgresServer {
    private PostgreSQLContainer<?> container() {
        return DockerPostgresSingleton.SINGLETON;
    }

    @Override
    public String host() {
        return container().getHost();
    }

    @Override
    public int port() {
        return container().getMappedPort(PostgresFixture.PORT);
    }

    @Override
    public Database scoped(Database database) {
        return database;
    }

    @Override
    public void start() {
        if (!container().isRunning()) {
            container().start();
        }
    }

    @Override
    public void pause() {
        container().getDockerClient().pauseContainerCmd(container().getContainerId()).exec();
    }

    @Override
    public void unpause() {
        container().getDockerClient().unpauseContainerCmd(container().getContainerId()).exec();
    }

    @Override
    public void restart() {
        container().stop();
        container().start();
    }
}
