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
import java.util.Optional;

import org.apache.james.backends.postgres.PostgresFixture.Database;
import org.apache.james.backends.postgres.PostgresServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OdooAddressesDatabaseTest {
    private static final PostgresServer SERVER = PostgresServer.get();
    private static final Database ADMIN = Database.DEFAULT_DATABASE;
    private static final String DATABASE = "odoo_addresses_t" + ProcessHandle.current().pid();
    private static final String READER = "james_odoo_reader_t" + ProcessHandle.current().pid();

    @BeforeAll
    static void createOdoo() {
        SERVER.start();
        dropOdoo();
        SERVER.execute(ADMIN, "postgres", "CREATE DATABASE " + DATABASE + " OWNER " + ADMIN.dbUser());
        SERVER.execute(ADMIN, "postgres", "CREATE ROLE " + READER + " LOGIN PASSWORD 'reader'");
        SERVER.execute(ADMIN, DATABASE, "CREATE TABLE mail_alias (alias_full_name varchar, active boolean)");
        SERVER.execute(ADMIN, DATABASE, "INSERT INTO mail_alias VALUES ('Ventas@AgroMarin.test', true), "
            + "('compras@agromarin.test', true), (NULL, true)");
        SERVER.execute(ADMIN, DATABASE, "CREATE VIEW mail_gateway_recipient AS "
            + "SELECT lower(alias_full_name) AS address, 'alias'::varchar AS kind FROM mail_alias");
        SERVER.execute(ADMIN, DATABASE, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
        SERVER.execute(ADMIN, DATABASE, "GRANT USAGE ON SCHEMA public TO " + READER);
        SERVER.execute(ADMIN, DATABASE, "GRANT SELECT ON mail_gateway_recipient TO " + READER);
    }

    @AfterAll
    static void dropOdoo() {
        SERVER.execute(ADMIN, "postgres", "DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        SERVER.execute(ADMIN, "postgres", "DROP ROLE IF EXISTS " + READER);
    }

    private static OdooConfiguration configuration(String user, String password) {
        return new OdooConfiguration("jdbc:postgresql://" + SERVER.host() + ":" + SERVER.port() + "/" + DATABASE,
            user, Optional.of(password), Duration.ofSeconds(30));
    }

    @Test
    void aReadOnlyRoleOnTheViewReadsEveryAddressLowerCased() throws Exception {
        assertThat(OdooAddresses.database(configuration(READER, "reader")).load())
            .containsExactlyInAnyOrder("ventas@agromarin.test", "compras@agromarin.test");
    }

    @Test
    void theRoleReadsNothingButTheView() {
        assertThatThrownBy(() -> SERVER.execute(new Database() {
            @Override
            public String dbUser() {
                return READER;
            }

            @Override
            public String dbPassword() {
                return "reader";
            }

            @Override
            public String dbName() {
                return DATABASE;
            }

            @Override
            public String schema() {
                return "public";
            }
        }, DATABASE, "SELECT * FROM mail_alias")).hasMessageContaining("permission denied");
    }

    @Test
    void aRefusedLoginIsAnErrorNotAnEmptySet() {
        assertThatThrownBy(() -> OdooAddresses.database(configuration(READER, "wrong")).load())
            .isInstanceOf(SQLException.class);
    }
}
