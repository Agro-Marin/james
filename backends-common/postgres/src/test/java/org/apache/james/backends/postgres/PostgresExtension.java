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
import static org.apache.james.backends.postgres.PostgresFixture.Database.ROW_LEVEL_SECURITY_DATABASE;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.james.GuiceModuleTestExtension;
import org.apache.james.backends.postgres.utils.JamesPostgresConnectionFactory;
import org.apache.james.backends.postgres.utils.PoolBackedPostgresConnectionFactory;
import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.inject.Module;
import com.google.inject.util.Modules;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresExtension implements GuiceModuleTestExtension {
    public enum PoolSize {
        SMALL(10, 20),
        LARGE(20, 40);

        private final int min;
        private final int max;

        PoolSize(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public int getMin() {
            return min;
        }

        public int getMax() {
            return max;
        }
    }

    public static PostgresExtension withRowLevelSecurity(PostgresDataDefinition module) {
        return new PostgresExtension(module, RowLevelSecurity.ENABLED);
    }

    public static PostgresExtension withoutRowLevelSecurity(PostgresDataDefinition module) {
        return withoutRowLevelSecurity(module, PoolSize.SMALL);
    }

    public static PostgresExtension withoutRowLevelSecurity(PostgresDataDefinition module, PoolSize poolSize) {
        return new PostgresExtension(module, RowLevelSecurity.DISABLED, Optional.of(poolSize));
    }

    public static PostgresExtension empty() {
        return withoutRowLevelSecurity(PostgresDataDefinition.EMPTY_MODULE);
    }

    public static final PoolSize DEFAULT_POOL_SIZE = PoolSize.SMALL;
    public static final Duration DEFAULT_JOOQ_REACTIVE_TIMEOUT = Duration.ofSeconds(20L);
    private static final PostgresServer SERVER = PostgresServer.get();
    private final PostgresDataDefinition postgresDataDefinition;
    private final RowLevelSecurity rowLevelSecurity;
    private final PostgresFixture.Database selectedDatabase;
    private PoolSize poolSize;
    private Duration jooqReactiveTimeout = DEFAULT_JOOQ_REACTIVE_TIMEOUT;
    private PostgresConfiguration postgresConfiguration;
    private PostgresExecutor defaultPostgresExecutor;
    private PostgresExecutor byPassRLSPostgresExecutor;
    private PostgresqlConnectionFactory connectionFactory;
    private Connection defaultConnection;
    private PostgresExecutor.Factory executorFactory;
    private PostgresTableManager postgresTableManager;

    public PostgresExtension withJooqReactiveTimeout(Duration jooqReactiveTimeout) {
        this.jooqReactiveTimeout = jooqReactiveTimeout;
        return this;
    }

    public void pause() {
        SERVER.pause();
    }

    public void unpause() {
        SERVER.unpause();
    }

    private PostgresExtension(PostgresDataDefinition postgresDataDefinition, RowLevelSecurity rowLevelSecurity) {
        this(postgresDataDefinition, rowLevelSecurity, Optional.empty());
    }

    private PostgresExtension(PostgresDataDefinition postgresDataDefinition, RowLevelSecurity rowLevelSecurity, Optional<PoolSize> maybePoolSize) {
        this.postgresDataDefinition = postgresDataDefinition;
        this.rowLevelSecurity = rowLevelSecurity;
        if (rowLevelSecurity.isRowLevelSecurityEnabled()) {
            this.selectedDatabase = SERVER.scoped(ROW_LEVEL_SECURITY_DATABASE);
        } else {
            this.selectedDatabase = SERVER.scoped(DEFAULT_DATABASE);
        }
        this.poolSize = maybePoolSize.orElse(DEFAULT_POOL_SIZE);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        SERVER.start();
        querySettingRowLevelSecurityIfNeed();
        querySettingExtension();
        initPostgresSession();
    }

    private void querySettingRowLevelSecurityIfNeed() {
        if (rowLevelSecurity.isRowLevelSecurityEnabled()) {
            PostgresFixture.Database main = SERVER.scoped(DEFAULT_DATABASE);
            SERVER.execute(main, main.dbName(), String.format(
                "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%1$s') THEN CREATE ROLE %1$s LOGIN PASSWORD '%2$s'; END IF; END $$",
                selectedDatabase.dbUser(), selectedDatabase.dbPassword()));
            if (!SERVER.exists(main, main.dbName(), "SELECT EXISTS (SELECT FROM pg_database WHERE datname = '" + selectedDatabase.dbName() + "')")) {
                SERVER.execute(main, main.dbName(), "CREATE DATABASE " + selectedDatabase.dbName() + " OWNER " + selectedDatabase.dbUser());
            }
            SERVER.execute(selectedDatabase, selectedDatabase.dbName(), "CREATE SCHEMA IF NOT EXISTS " + selectedDatabase.schema());
        }
    }

    private void querySettingExtension() {
        SERVER.execute(SERVER.scoped(DEFAULT_DATABASE), selectedDatabase.dbName(),
            String.format("CREATE EXTENSION IF NOT EXISTS hstore SCHEMA %s", selectedDatabase.schema()));
    }

    private void initPostgresSession() {
        postgresConfiguration = PostgresConfiguration.builder()
            .databaseName(selectedDatabase.dbName())
            .databaseSchema(selectedDatabase.schema())
            .host(getHost())
            .port(getMappedPort())
            .username(selectedDatabase.dbUser())
            .password(selectedDatabase.dbPassword())
            .byPassRLSUser(DEFAULT_DATABASE.dbUser())
            .byPassRLSPassword(DEFAULT_DATABASE.dbPassword())
            .rowLevelSecurityEnabled(rowLevelSecurity.isRowLevelSecurityEnabled())
            .jooqReactiveTimeout(Optional.of(jooqReactiveTimeout))
            .build();

        Function<PostgresConfiguration.Credential, PostgresqlConnectionConfiguration> postgresqlConnectionConfigurationFunction = credential ->
            PostgresqlConnectionConfiguration.builder()
                .host(postgresConfiguration.getHost())
                .port(postgresConfiguration.getPort())
                .database(postgresConfiguration.getDatabaseName())
                .schema(postgresConfiguration.getDatabaseSchema())
                .username(credential.getUsername())
                .password(credential.getPassword())
                .build();

        RecordingMetricFactory metricFactory = new RecordingMetricFactory();

        connectionFactory = new PostgresqlConnectionFactory(postgresqlConnectionConfigurationFunction.apply(postgresConfiguration.getDefaultCredential()));
        defaultConnection = connectionFactory.create().block();
        executorFactory = new PostgresExecutor.Factory(
            getJamesPostgresConnectionFactory(rowLevelSecurity, connectionFactory),
            postgresConfiguration,
            metricFactory);

        defaultPostgresExecutor = executorFactory.create();

        PostgresqlConnectionFactory byPassRLSConnectionFactory = new PostgresqlConnectionFactory(postgresqlConnectionConfigurationFunction.apply(postgresConfiguration.getByPassRLSCredential()));

        byPassRLSPostgresExecutor = new PostgresExecutor.Factory(
            getJamesPostgresConnectionFactory(RowLevelSecurity.DISABLED, byPassRLSConnectionFactory),
            postgresConfiguration,
            metricFactory)
            .create();

        this.postgresTableManager = new PostgresTableManager(defaultPostgresExecutor, postgresDataDefinition, rowLevelSecurity);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        disposePostgresSession();
    }

    private void disposePostgresSession() {
        defaultPostgresExecutor.dispose();
        byPassRLSPostgresExecutor.dispose();
        Mono.from(defaultConnection.close()).subscribe();
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        initTablesAndIndexes();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        resetSchema();
    }

    public void restartContainer() {
        SERVER.restart();
        initPostgresSession();
    }

    @Override
    public Module getModule() {
        return Modules.combine(binder -> binder.bind(PostgresConfiguration.class)
            .toInstance(postgresConfiguration));
    }

    public String getHost() {
        return SERVER.host();
    }

    public Integer getMappedPort() {
        return SERVER.port();
    }

    public Mono<Connection> getConnection() {
        return Mono.just(defaultConnection);
    }

    public PostgresExecutor getDefaultPostgresExecutor() {
        return defaultPostgresExecutor;
    }

    public PostgresExecutor getByPassRLSPostgresExecutor() {
        return byPassRLSPostgresExecutor;
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public PostgresExecutor.Factory getExecutorFactory() {
        return executorFactory;
    }

    public PostgresConfiguration getPostgresConfiguration() {
        return postgresConfiguration;
    }

    private void initTablesAndIndexes() {
        postgresTableManager.initializeTables().block();
        postgresTableManager.initializeTableIndexes().block();
    }

    private void resetSchema() {
        List<String> tables = postgresTableManager.listExistTables().block();
        dropTables(tables);
    }

    private void dropTables(List<String> tables) {
        String tablesToDelete = tables.stream()
            .map(tableName -> "\"" + tableName + "\"")
            .collect(Collectors.joining(", "));

        Flux.from(defaultConnection.createStatement(String.format("DROP table if exists %s cascade;", tablesToDelete))
            .execute())
            .then()
            .block();
    }

    private JamesPostgresConnectionFactory getJamesPostgresConnectionFactory(RowLevelSecurity rowLevelSecurity, PostgresqlConnectionFactory connectionFactory) {
        return new PoolBackedPostgresConnectionFactory(
            rowLevelSecurity,
            poolSize.getMin(),
            poolSize.getMax(),
            connectionFactory);
    }
}
