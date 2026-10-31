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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.apache.james.events.Group;
import org.apache.james.mailbox.AttachmentContentLoader;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.SessionProvider;
import org.apache.james.mailbox.events.MailboxEvents.Added;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.store.MailboxSessionMapperFactory;
import org.apache.james.mailbox.store.mail.MessageMapper.FetchType;
import org.apache.james.mailbox.store.search.SearchText;
import org.apache.james.util.ReactorUtils;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps the text of each added message in message_search_text, once per message: a copy or a move adds no row. A message
 * deleted from the message table takes its row with it.
 */
public class PostgresSearchTextIndexer implements EventListener.ReactiveGroupEventListener {
    public static class PostgresSearchTextIndexerGroup extends Group {
    }

    private static final int UNLIMITED = -1;

    private final PostgresSearchTextDAO.Factory daoFactory;
    private final MailboxSessionMapperFactory mapperFactory;
    private final SessionProvider sessionProvider;
    private final TextExtractor textExtractor;
    private final AttachmentContentLoader attachmentContentLoader;

    @Inject
    public PostgresSearchTextIndexer(PostgresSearchTextDAO.Factory daoFactory, MailboxSessionMapperFactory mapperFactory,
                                     SessionProvider sessionProvider, TextExtractor textExtractor,
                                     AttachmentContentLoader attachmentContentLoader) {
        this.daoFactory = daoFactory;
        this.mapperFactory = mapperFactory;
        this.sessionProvider = sessionProvider;
        this.textExtractor = textExtractor;
        this.attachmentContentLoader = attachmentContentLoader;
    }

    @Override
    public Group getDefaultGroup() {
        return new PostgresSearchTextIndexerGroup();
    }

    @Override
    public boolean isHandling(Event event) {
        return event instanceof Added;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        if (!(event instanceof Added added)) {
            return Mono.empty();
        }
        MailboxSession session = sessionProvider.createSystemSession(added.getUsername());
        PostgresSearchTextDAO dao = daoFactory.create(added.getUsername().getDomainPart());
        return Flux.fromIterable(added.getAdded().values())
            .filterWhen(metaData -> dao.exists((PostgresMessageId) metaData.getMessageId()).map(exists -> !exists))
            .map(metaData -> metaData.getUid())
            .collectList()
            .filter(uids -> !uids.isEmpty())
            .flatMap(uids -> mapperFactory.getMailboxMapper(session).findMailboxById(added.getMailboxId())
                .flatMap(mailbox -> index(session, dao, mailbox, uids)))
            .then(Mono.fromRunnable(() -> mapperFactory.endProcessingRequest(session)));
    }

    private Mono<Void> index(MailboxSession session, PostgresSearchTextDAO dao, Mailbox mailbox, List<MessageUid> uids) {
        return Flux.fromIterable(MessageRange.toRanges(uids))
            .concatMap(range -> mapperFactory.getMessageMapper(session).findInMailboxReactive(mailbox, range, FetchType.FULL, UNLIMITED))
            .concatMap(message -> Mono.fromCallable(() -> SearchText.of(message, textExtractor, attachmentContentLoader, session))
                .subscribeOn(ReactorUtils.BLOCKING_CALL_WRAPPER)
                .flatMap(text -> dao.insert((PostgresMessageId) message.getMessageId(), text)))
            .then();
    }
}
