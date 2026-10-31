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

package org.apache.james.mailbox.postgres.search;

import static org.apache.james.backends.postgres.PostgresCommons.tableField;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable;
import static org.apache.james.mailbox.postgres.search.PostgresSearchTextDataDefinition.SearchTextTable;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.core.Domain;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.store.search.SearchText;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import com.google.common.base.Throwables;

import io.r2dbc.spi.R2dbcException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresSearchTextDAO {
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    public static class Factory {
        private final PostgresExecutor.Factory executorFactory;

        @Inject
        @Singleton
        public Factory(PostgresExecutor.Factory executorFactory) {
            this.executorFactory = executorFactory;
        }

        public PostgresSearchTextDAO create(Optional<Domain> domain) {
            return new PostgresSearchTextDAO(executorFactory.create(domain));
        }
    }

    private final PostgresExecutor postgresExecutor;

    public PostgresSearchTextDAO(PostgresExecutor postgresExecutor) {
        this.postgresExecutor = postgresExecutor;
    }

    public Mono<Void> insert(PostgresMessageId messageId, SearchText text) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.insertInto(SearchTextTable.TABLE_NAME)
            .set(SearchTextTable.MESSAGE_ID, messageId.asUuid())
            .set(SearchTextTable.HEADERS, text.headers())
            .set(SearchTextTable.BODIES, text.bodies())
            .set(SearchTextTable.ATTACHMENTS, text.attachments())
            .set(SearchTextTable.ATTACHMENT_NAMES, text.attachmentNames())
            .onConflict(SearchTextTable.MESSAGE_ID)
            .doNothing()))
            .onErrorResume(PostgresSearchTextDAO::isMessageGone, e -> Mono.empty());
    }

    // A message expunged and deleted while its text was read has nothing left to index
    private static boolean isMessageGone(Throwable throwable) {
        return Throwables.getCausalChain(throwable).stream()
            .anyMatch(cause -> cause instanceof R2dbcException exception && FOREIGN_KEY_VIOLATION.equals(exception.getSqlState()));
    }

    public Mono<Boolean> exists(PostgresMessageId messageId) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.selectOne()
                .from(SearchTextTable.TABLE_NAME)
                .where(SearchTextTable.MESSAGE_ID.eq(messageId.asUuid()))))
            .hasElement();
    }

    /**
     * The UIDs of the mailbox's messages whose text matches the condition, and of those with no text yet: a message not
     * indexed yet may match too.
     */
    public Flux<MessageUid> candidates(PostgresMailboxId mailboxId, Condition textCondition) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select(MessageToMailboxTable.MESSAGE_UID)
                .from(MessageToMailboxTable.TABLE_NAME)
                .leftJoin(SearchTextTable.TABLE_NAME)
                .on(tableField(SearchTextTable.TABLE_NAME, SearchTextTable.MESSAGE_ID)
                    .eq(tableField(MessageToMailboxTable.TABLE_NAME, MessageToMailboxTable.MESSAGE_ID)))
                .where(MessageToMailboxTable.MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(DSL.or(tableField(SearchTextTable.TABLE_NAME, SearchTextTable.MESSAGE_ID).isNull(), textCondition))))
            .map(record -> MessageUid.of(record.get(MessageToMailboxTable.MESSAGE_UID)));
    }
}
