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

package org.apache.james.mailbox.postgres.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.postgres.PostgresMailboxAggregateDataDefinition;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable;
import org.apache.james.mailbox.store.mail.model.MapperProvider;
import org.apache.james.mailbox.store.mail.model.MessageMapperTest;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import reactor.core.publisher.Mono;

public class PostgresMessageMapperTest extends MessageMapperTest {
    private static final Duration ONCE_THE_TRANSACTION_HOLDS_THE_ROW = Duration.ofMillis(200);
    private static final Duration UNTIL_THE_MAPPER_WAITS_FOR_THE_ROW = Duration.ofMillis(1500);

    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresMailboxAggregateDataDefinition.MODULE);

    private PostgresMapperProvider postgresMapperProvider;

    @Override
    protected MapperProvider createMapperProvider() {
        postgresMapperProvider = new PostgresMapperProvider(postgresExtension);
        return postgresMapperProvider;
    }

    @Override
    protected UpdatableTickingClock updatableTickingClock() {
        return postgresMapperProvider.getUpdatableTickingClock();
    }

    // Two sessions expunging one message: the mapper's read still sees it, its DELETE waits for the other to commit
    // and then removes nothing. Reporting it expunged anyway decreased the quota a second time.
    @Test
    void deleteMessagesShouldNotReportAMessageAConcurrentDeleteRemoved() throws Exception {
        saveMessages();
        PostgresExecutor executor = postgresExtension.getDefaultPostgresExecutor();
        Mono<Long> concurrentDelete = executor.executeTransaction(dslContext -> Mono.from(dslContext.deleteFrom(MessageToMailboxTable.TABLE_NAME)
                .where(MessageToMailboxTable.MAILBOX_ID.eq(((PostgresMailboxId) benwaInboxMailbox.getMailboxId()).asUuid()))
                .and(MessageToMailboxTable.MESSAGE_UID.eq(message1.getUid().asLong())))
            .then(Mono.delay(UNTIL_THE_MAPPER_WAITS_FOR_THE_ROW)));
        Mono<Map<MessageUid, MessageMetaData>> mapperDelete = Mono.delay(ONCE_THE_TRANSACTION_HOLDS_THE_ROW)
            .then(messageMapper.deleteMessagesReactive(benwaInboxMailbox, List.of(message1.getUid())));

        Map<MessageUid, MessageMetaData> reported = Mono.zip(concurrentDelete, mapperDelete)
            .map(results -> results.getT2())
            .block(Duration.ofSeconds(30));

        assertThat(reported).isEmpty();
        assertThat(messageMapper.countMessagesInMailbox(benwaInboxMailbox)).isEqualTo(4);
    }
}
