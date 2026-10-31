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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BucketName;
import org.apache.james.blob.api.PlainBlobId;
import org.apache.james.blob.memory.MemoryBlobStoreDAO;
import org.apache.james.events.EventBusTestFixture;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.mailbox.AttachmentContentLoader;
import org.apache.james.mailbox.SessionProvider;
import org.apache.james.mailbox.StringBackedAttachmentIdFactory;
import org.apache.james.mailbox.acl.UnionMailboxACLResolver;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.postgres.PostgresMailboxAggregateDataDefinition;
import org.apache.james.mailbox.postgres.PostgresMailboxManager;
import org.apache.james.mailbox.postgres.PostgresMailboxSessionMapperFactory;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.store.PreDeletionHooks;
import org.apache.james.mailbox.store.SessionProviderImpl;
import org.apache.james.mailbox.store.StoreAttachmentManager;
import org.apache.james.mailbox.store.StoreMailboxAnnotationManager;
import org.apache.james.mailbox.store.StoreMessageIdManager;
import org.apache.james.mailbox.store.StoreRightManager;
import org.apache.james.mailbox.store.extractor.JsoupTextExtractor;
import org.apache.james.mailbox.store.mail.AttachmentIdAssignationStrategy;
import org.apache.james.mailbox.store.mail.NaiveThreadIdGuessingAlgorithm;
import org.apache.james.mailbox.store.mail.model.impl.MessageParserImpl;
import org.apache.james.mailbox.store.quota.QuotaComponents;
import org.apache.james.mailbox.store.search.AbstractMessageSearchIndexTest;
import org.apache.james.mailbox.store.search.SearchCandidates;
import org.apache.james.mailbox.store.search.SimpleMessageSearchIndex;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.blob.deduplication.DeDuplicationBlobStore;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PostgresMessageSearchIndexTest extends AbstractMessageSearchIndexTest {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresMailboxAggregateDataDefinition.MODULE);

    @Override
    protected void awaitMessageCount(List<MailboxId> mailboxIds, SearchQuery query, long messageCount) {
    }

    @Override
    protected void initializeMailboxManager() {
        UpdatableTickingClock clock = new UpdatableTickingClock(Instant.now());
        BlobId.Factory blobIdFactory = new PlainBlobId.Factory();
        DeDuplicationBlobStore blobStore = new DeDuplicationBlobStore(new MemoryBlobStoreDAO(), BucketName.DEFAULT, blobIdFactory);
        PostgresMailboxSessionMapperFactory mapperFactory = new PostgresMailboxSessionMapperFactory(postgresExtension.getExecutorFactory(),
            clock, blobStore, blobIdFactory, postgresExtension.getPostgresConfiguration(),
            new AttachmentIdAssignationStrategy.Default(new StringBackedAttachmentIdFactory()));

        InVMEventBus inVMEventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), EventBusTestFixture.RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        StoreRightManager storeRightManager = new StoreRightManager(mapperFactory, new UnionMailboxACLResolver(), inVMEventBus);
        SessionProviderImpl sessionProvider = new SessionProviderImpl(null, null);
        QuotaComponents quotaComponents = QuotaComponents.disabled(sessionProvider, mapperFactory);
        AtomicReference<StoreAttachmentManager> attachmentManager = new AtomicReference<>();
        AttachmentContentLoader attachmentContentLoader = (attachment, mailboxSession) -> attachmentManager.get().load(attachment, mailboxSession);
        TextExtractor textExtractor = new JsoupTextExtractor();
        SimpleMessageSearchIndex searchIndex = new SimpleMessageSearchIndex(mapperFactory, mapperFactory, textExtractor,
            attachmentContentLoader, searchCandidates());

        PostgresMailboxManager mailboxManager = new PostgresMailboxManager(mapperFactory, sessionProvider,
            new MessageParserImpl(), new PostgresMessageId.Factory(), inVMEventBus,
            new StoreMailboxAnnotationManager(mapperFactory, storeRightManager), storeRightManager,
            quotaComponents, searchIndex, new NaiveThreadIdGuessingAlgorithm(),
            PreDeletionHooks.NO_PRE_DELETION_HOOK, clock);

        registerListeners(inVMEventBus, mapperFactory, sessionProvider, textExtractor, attachmentContentLoader);
        storeMailboxManager = mailboxManager;
        messageIdManager = new StoreMessageIdManager(mailboxManager, mapperFactory, inVMEventBus,
            quotaComponents.getQuotaManager(), quotaComponents.getQuotaRootResolver(), PreDeletionHooks.NO_PRE_DELETION_HOOK);
        attachmentManager.set(new StoreAttachmentManager(mapperFactory, messageIdManager));
        messageSearchIndex = searchIndex;
        eventBus = inVMEventBus;
        messageIdFactory = new PostgresMessageId.Factory();
    }

    protected SearchCandidates searchCandidates() {
        return SearchCandidates.ALL;
    }

    protected void registerListeners(InVMEventBus eventBus, PostgresMailboxSessionMapperFactory mapperFactory, SessionProvider sessionProvider,
                                     TextExtractor textExtractor, AttachmentContentLoader attachmentContentLoader) {
    }

    @Override
    protected MessageId initNewBasedMessageId() {
        return PostgresMessageId.Factory.of(UUID.randomUUID());
    }

    @Override
    protected MessageId initOtherBasedMessageId() {
        return PostgresMessageId.Factory.of(UUID.randomUUID());
    }

    @Override
    protected boolean supportsCollapseThreads() {
        return true;
    }

    @Test
    @Disabled(RECENT_IS_THE_SESSIONS)
    @Override
    protected void flagIsSetShouldReturnUidOfMessageMarkedAsRecentWhenUsedWithFlagRecent() {
    }

    @Test
    @Disabled(RECENT_IS_THE_SESSIONS)
    @Override
    protected void flagIsUnSetShouldReturnUidOfMessageNotMarkedAsRecentWhenUsedWithFlagRecent() {
    }

    @Test
    @Disabled(EMPTY_UID_SET_MATCHES_NOTHING)
    @Override
    protected void uidShouldreturnEveryThing() {
    }

    @Test
    @Disabled(SORT_ON_ADDR_MAILBOX)
    @Override
    protected void sortOnCcShouldWork() {
    }

    @Test
    @Disabled(SORT_ON_ADDR_MAILBOX)
    @Override
    protected void sortOnFromShouldWork() {
    }

    @Test
    @Disabled(SORT_ON_ADDR_MAILBOX)
    @Override
    protected void sortOnToShouldWork() {
    }

    @Test
    @Disabled(BODY_IS_A_SUBSTRING)
    @Override
    protected void bodyContainsShouldReturnUidOfMessageContainingBothTerms() {
    }

    @Test
    @Disabled(SENT_DATE_IS_NO_HEADER)
    @Override
    protected void headerDateAfterShouldWork() {
    }

    @Test
    @Disabled(SENT_DATE_IS_NO_HEADER)
    @Override
    protected void headerDateBeforeShouldWork() {
    }

    @Test
    @Disabled(SENT_DATE_IS_NO_HEADER)
    @Override
    protected void headerDateOnShouldWork() {
    }

    @Test
    @Disabled("PDF text needs Tika, and the deployed app runs without it: JsoupTextExtractor reads text and HTML only")
    @Override
    protected void searchWithPDFAttachmentShouldReturnMailsWhenAttachmentContentMatches() {
    }
}
