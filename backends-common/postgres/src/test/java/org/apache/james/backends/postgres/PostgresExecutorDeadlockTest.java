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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PostgresExecutorDeadlockTest {
    private static final Table<Record> TABLE = DSL.table("deadlock_test");
    private static final Field<Integer> ID = DSL.field("id", SQLDataType.INTEGER);
    private static final Duration ONCE_THE_TRANSACTION_HOLDS_ROW_2 = Duration.ofMillis(200);
    private static final Duration ONCE_THE_STATEMENT_HOLDS_ROW_1 = Duration.ofMillis(600);

    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.empty();

    private PostgresExecutor executor;

    @BeforeEach
    void beforeEach() {
        executor = postgresExtension.getDefaultPostgresExecutor();
        executor.executeVoid(dslContext -> Mono.from(dslContext.createTableIfNotExists(TABLE).column(ID))).block();
        Flux.range(1, 3)
            .concatMap(id -> executor.executeVoid(dslContext -> Mono.from(dslContext.insertInto(TABLE, ID).values(id))))
            .blockLast();
    }

    @AfterEach
    void afterEach() {
        executor.executeVoid(dslContext -> Mono.from(dslContext.dropTableIfExists(TABLE))).block();
    }

    // The statement waits for row 2 before the transaction waits for row 1, so its deadlock check runs first and
    // PostgreSQL chooses it as the victim.
    @Test
    void aStatementChosenAsADeadlockVictimShouldBeRunAgain() {
        Mono<Void> transaction = executor.executeTransaction(dslContext -> Mono.from(dslContext.deleteFrom(TABLE).where(ID.eq(2)))
                .then(Mono.delay(ONCE_THE_STATEMENT_HOLDS_ROW_1))
                .then(Mono.from(dslContext.deleteFrom(TABLE).where(ID.eq(1)))))
            .then();
        Mono<List<Integer>> statement = Mono.delay(ONCE_THE_TRANSACTION_HOLDS_ROW_2)
            .thenMany(executor.executeDeleteAndReturnList(dslContext -> dslContext.deleteFrom(TABLE).where(ID.in(1, 2)).returning(ID)))
            .map(record -> record.get(ID))
            .collectList();

        List<Integer> deletedByTheStatement = Mono.zip(transaction.thenReturn(true), statement)
            .map(results -> results.getT2())
            .block(Duration.ofSeconds(30));

        assertThat(deletedByTheStatement).isEmpty();
        assertThat(executor.executeRows(dslContext -> Flux.from(dslContext.select(ID).from(TABLE)))
            .map(record -> record.get(ID))
            .collectList()
            .block())
            .containsExactly(3);
    }
}
