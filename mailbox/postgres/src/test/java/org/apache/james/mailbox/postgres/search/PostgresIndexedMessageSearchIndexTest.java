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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.mail.Flags;

import org.apache.james.events.InVMEventBus;
import org.apache.james.mailbox.AttachmentContentLoader;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.SessionProvider;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.postgres.PostgresMailboxSessionMapperFactory;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.store.StoreMessageManager;
import org.apache.james.mailbox.store.search.SearchCandidates;
import org.apache.james.mailbox.store.search.SearchText;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class PostgresIndexedMessageSearchIndexTest extends PostgresMessageSearchIndexTest {
    private PostgresSearchTextDAO dao() {
        return new PostgresSearchTextDAO.Factory(postgresExtension.getExecutorFactory()).create(Optional.empty());
    }

    @Override
    protected SearchCandidates searchCandidates() {
        return new PostgresSearchCandidates(new PostgresSearchTextDAO.Factory(postgresExtension.getExecutorFactory()));
    }

    @Override
    protected void registerListeners(InVMEventBus eventBus, PostgresMailboxSessionMapperFactory mapperFactory, SessionProvider sessionProvider,
                                     TextExtractor textExtractor, AttachmentContentLoader attachmentContentLoader) {
        eventBus.register(new PostgresSearchTextIndexer(new PostgresSearchTextDAO.Factory(postgresExtension.getExecutorFactory()),
            mapperFactory, sessionProvider, textExtractor, attachmentContentLoader));
    }

    private ComposedMessageId append(MessageManager messageManager, String message) throws Exception {
        ComposedMessageId id = messageManager.appendMessage(MessageManager.AppendCommand.builder()
            .build(message.getBytes(StandardCharsets.UTF_8)), session).getId();
        await().atMost(Duration.ofSeconds(10))
            .until(() -> dao().exists((PostgresMessageId) id.getMessageId()).block());
        return id;
    }

    private MessageManager newMailbox() throws Exception {
        MailboxPath path = MailboxPath.forUser(USERNAME, "candidates");
        storeMailboxManager.createMailbox(path, session);
        return storeMailboxManager.getMailbox(path, session);
    }

    private Optional<Collection<MessageUid>> candidates(MessageManager messageManager, SearchQuery.Criterion... criteria) throws Exception {
        return searchCandidates().of(session, ((StoreMessageManager) messageManager).getMailboxEntity(),
            SearchQuery.of(criteria));
    }

    @Test
    void bodyCriterionShouldNarrowToTheMessagesHoldingTheText() throws Exception {
        MessageManager messageManager = newMailbox();
        ComposedMessageId hit = append(messageManager, "Subject: a\r\n\r\nthe zqxneedle is here\r\n");
        append(messageManager, "Subject: b\r\n\r\nnothing to see\r\n");

        assertThat(candidates(messageManager, SearchQuery.bodyContains("ZqxNeedle")))
            .contains(List.of(hit.getUid()));
    }

    @Test
    void aMessageNotIndexedYetShouldBeACandidate() throws Exception {
        MessageManager messageManager = newMailbox();
        append(messageManager, "Subject: a\r\n\r\nindexed\r\n");
        ComposedMessageId unindexed = append(messageManager, "Subject: b\r\n\r\nzqxneedle\r\n");
        postgresExtension.getDefaultPostgresExecutor().executeVoid(dsl -> Mono.from(dsl.deleteFrom(
                PostgresSearchTextDataDefinition.SearchTextTable.TABLE_NAME)
            .where(PostgresSearchTextDataDefinition.SearchTextTable.MESSAGE_ID.eq(((PostgresMessageId) unindexed.getMessageId()).asUuid()))))
            .block();

        assertThat(candidates(messageManager, SearchQuery.bodyContains("absent")))
            .contains(List.of(unindexed.getUid()));
    }

    @Test
    void addressCriterionShouldNarrowByTheFormattedAddress() throws Exception {
        MessageManager messageManager = newMailbox();
        ComposedMessageId hit = append(messageManager, "From: \"Zqx Doe\" <zqx@example.com>\r\nSubject: a\r\n\r\nbody\r\n");
        append(messageManager, "From: other@example.com\r\nSubject: b\r\n\r\nbody\r\n");

        assertThat(candidates(messageManager, SearchQuery.address(SearchQuery.AddressType.From, "zqx doe <zqx@")))
            .contains(List.of(hit.getUid()));
    }

    @Test
    void anAndShouldNarrowByItsTextCriterion() throws Exception {
        MessageManager messageManager = newMailbox();
        ComposedMessageId hit = append(messageManager, "Subject: a\r\n\r\nzqxneedle\r\n");
        append(messageManager, "Subject: b\r\n\r\nother\r\n");

        assertThat(candidates(messageManager, SearchQuery.bodyContains("zqxneedle"), SearchQuery.flagIsSet(Flags.Flag.SEEN)))
            .contains(List.of(hit.getUid()));
    }

    @Test
    void anOrWithACriterionTheIndexCannotTellShouldNotNarrow() throws Exception {
        MessageManager messageManager = newMailbox();
        append(messageManager, "Subject: a\r\n\r\nzqxneedle\r\n");

        assertThat(candidates(messageManager, SearchQuery.or(SearchQuery.bodyContains("zqxneedle"), SearchQuery.flagIsSet(Flags.Flag.SEEN))))
            .isEmpty();
    }

    @Test
    void aNotShouldNotNarrow() throws Exception {
        MessageManager messageManager = newMailbox();
        append(messageManager, "Subject: a\r\n\r\nzqxneedle\r\n");

        assertThat(candidates(messageManager, SearchQuery.not(SearchQuery.bodyContains("zqxneedle"))))
            .isEmpty();
    }

    @Test
    void likeWildcardsInTheTextShouldBeLiteral() throws Exception {
        MessageManager messageManager = newMailbox();
        ComposedMessageId hit = append(messageManager, "Subject: a\r\n\r\n50%_off\r\n");
        append(messageManager, "Subject: b\r\n\r\n50 xoff\r\n");

        assertThat(candidates(messageManager, SearchQuery.bodyContains("50%_off")))
            .contains(List.of(hit.getUid()));
    }

    @Test
    void aCopyShouldBeFoundByTheOriginalsText() throws Exception {
        MessageManager messageManager = newMailbox();
        ComposedMessageId original = append(messageManager, "Subject: a\r\n\r\nzqxneedle\r\n");
        MailboxPath copyPath = MailboxPath.forUser(USERNAME, "copies");
        storeMailboxManager.createMailbox(copyPath, session);
        storeMailboxManager.copyMessages(MessageRange.one(original.getUid()),
            MailboxPath.forUser(USERNAME, "candidates"), copyPath, session);

        assertThat(candidates(storeMailboxManager.getMailbox(copyPath, session), SearchQuery.bodyContains("zqxneedle")))
            .hasValueSatisfying(uids -> assertThat(uids).hasSize(1));
    }

    @Test
    void theTextOfAMessageDeletedMeanwhileShouldBeDropped() {
        PostgresMessageId gone = new PostgresMessageId.Factory().generate();

        dao().insert(gone, new SearchText("", "", "", "")).block();

        assertThat(dao().exists(gone).block()).isFalse();
    }
}
