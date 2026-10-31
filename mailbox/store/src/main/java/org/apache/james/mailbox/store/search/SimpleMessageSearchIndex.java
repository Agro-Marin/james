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
package org.apache.james.mailbox.store.search;

import static org.apache.james.mailbox.store.mail.AbstractMessageMapper.UNLIMITED;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.mailbox.AttachmentContentLoader;
import org.apache.james.mailbox.MailboxManager.MessageCapabilities;
import org.apache.james.mailbox.MailboxManager.SearchCapabilities;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.SearchOptions;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.SearchQuery.ConjunctionCriterion;
import org.apache.james.mailbox.model.SearchQuery.Criterion;
import org.apache.james.mailbox.model.SearchQuery.UidCriterion;
import org.apache.james.mailbox.store.mail.MailboxMapper;
import org.apache.james.mailbox.store.mail.MailboxMapperFactory;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.MessageMapper.FetchType;
import org.apache.james.mailbox.store.mail.MessageMapperFactory;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.util.ReactorUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link MessageSearchIndex} which just fetch {@link MailboxMessage}'s from the {@link MessageMapper} and use {@link MessageSearcher}
 * to match them against the {@link SearchQuery}.
 * 
 * This works with every implementation but is SLOW.
 * 
 *
 */
public class SimpleMessageSearchIndex implements MessageSearchIndex {

    private final MessageMapperFactory messageMapperFactory;
    private final MailboxMapperFactory mailboxMapperFactory;
    private final TextExtractor textExtractor;
    private final AttachmentContentLoader attachmentContentLoader;

    @Inject
    public SimpleMessageSearchIndex(MessageMapperFactory messageMapperFactory, MailboxMapperFactory mailboxMapperFactory, TextExtractor textExtractor, AttachmentContentLoader attachmentContentLoader) {
        this.messageMapperFactory = messageMapperFactory;
        this.mailboxMapperFactory = mailboxMapperFactory;
        this.textExtractor = textExtractor;
        this.attachmentContentLoader = attachmentContentLoader;
    }
    
    @Override
    public EnumSet<SearchCapabilities> getSupportedCapabilities(EnumSet<MessageCapabilities> messageCapabilities) {
        return EnumSet.of(SearchCapabilities.MultimailboxSearch,
            SearchCapabilities.Text,
            SearchCapabilities.Attachment,
            SearchCapabilities.PartialEmailMatch,
            SearchCapabilities.AttachmentFileName);
    }

    /**
     * Walks down the query tree's conjunctions to find a UidCriterion
     * @param crits - list of Criterion to search from
     * @return
     *      first UidCriterion found
     *      null - if not found
     */
    private static UidCriterion findConjugatedUidCriterion(List<Criterion> crits) {
        for (Criterion crit : crits) {
            if (crit instanceof UidCriterion) {
                return (UidCriterion) crit;
            }
            // Only an AND narrows what to fetch: under OR the range is one alternative, under NOT the excluded part
            if (crit instanceof ConjunctionCriterion conjunction && conjunction.getType() == SearchQuery.Conjunction.AND) {
                UidCriterion nested = findConjugatedUidCriterion(conjunction.getCriteria());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
    
    /**
     * Walks down the query tree's conjunctions to find the highest necessary mail fetch type.
     * @param crits - list of Criterion to search from
     * @return required fetch type - metadata, headers, or full
     */
    private static FetchType getFetchTypeForCriteria(List<Criterion> crits) {
        return crits.stream()
            .map(SimpleMessageSearchIndex::getFetchTypeForCriterion)
            .reduce(SimpleMessageSearchIndex::maxFetchType)
            .orElse(FetchType.METADATA);
    }

    private static FetchType getFetchTypeForCriterion(Criterion crit) {
        if (crit instanceof ConjunctionCriterion) {
            return getFetchTypeForCriteria(((ConjunctionCriterion) crit).getCriteria());
        }
        if (crit instanceof SearchQuery.AllCriterion || crit instanceof SearchQuery.TextCriterion) {
            return FetchType.FULL;
        }
        if (crit instanceof SearchQuery.HeaderCriterion || crit instanceof SearchQuery.MimeMessageIDCriterion
            || crit instanceof SearchQuery.SubjectCriterion) {
            return FetchType.HEADERS;
        }
        if (crit instanceof SearchQuery.AttachmentCriterion) {
            return FetchType.ATTACHMENTS_METADATA;
        }
        return FetchType.METADATA;
    }
    
    /**
     * Searches a list of query sort options for the highest necessary mail fetch type.
     * @param sorts - list of Sort to search
     * @return required fetch type - metadata or headers
     */
    private static FetchType getFetchTypeForSorts(List<SearchQuery.Sort> sorts) {
        return sorts.stream()
            .map(SimpleMessageSearchIndex::getFetchTypeForSort)
            .reduce(FetchType.METADATA, SimpleMessageSearchIndex::maxFetchType);
    }

    private static FetchType getFetchTypeForSort(SearchQuery.Sort sort) {
        switch (sort.getSortClause()) {
            case Arrival:
            case Size:
            case Uid:
            case Id:
                return FetchType.METADATA;
            case MailboxCc:
            case MailboxFrom:
            case MailboxTo:
            case BaseSubject:
            case SentDate:
                return FetchType.HEADERS;
            default:
                throw new IllegalArgumentException("cannot determine fetch type for sort option " + sort.getSortClause());
        }
    }

    @VisibleForTesting
    static FetchType maxFetchType(FetchType a, FetchType b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    @Override
    public Flux<MessageUid> search(MailboxSession session, final Mailbox mailbox, SearchQuery query) {
        Preconditions.checkArgument(session != null, "'session' is mandatory");
        return searchResults(session, Flux.just(mailbox), query)
            .filter(searchResult -> searchResult.getMailboxId().equals(mailbox.getMailboxId()))
            .map(SearchResult::getMessageUid);
    }

    private Collection<MailboxMessage> searchResults(MailboxSession session, Mailbox mailbox, SearchQuery query) throws MailboxException {
        MessageMapper mapper = messageMapperFactory.getMessageMapper(session);
        MessageSearches matcher = new MessageSearches(Collections.emptyIterator(), query, textExtractor, attachmentContentLoader, session);
        FetchType criteriaFetchType = getFetchTypeForCriteria(query.getCriteria());
        FetchType sortFetchType = getFetchTypeForSorts(query.getSorts());
        List<MessageRange> ranges = rangesToFetch(query);

        if (criteriaFetchType != FetchType.FULL) {
            SortedSet<MailboxMessage> hits = new TreeSet<>();
            forEachMessage(mapper, mailbox, ranges, maxFetchType(criteriaFetchType, sortFetchType), message -> {
                if (matcher.matches(message)) {
                    hits.add(message);
                }
            });
            return hits;
        }
        // A whole mailbox's content does not fit in memory: each message is matched as it is read and let go, and the
        // matches are read again with only what sorting needs
        SortedSet<MessageUid> hitUids = new TreeSet<>();
        forEachMessage(mapper, mailbox, ranges, FetchType.FULL, message -> {
            if (matcher.matches(message)) {
                hitUids.add(message.getUid());
            }
        });
        List<MailboxMessage> hits = new ArrayList<>();
        forEachMessage(mapper, mailbox, MessageRange.toRanges(hitUids), sortFetchType, hits::add);
        return hits;
    }

    private static List<MessageRange> rangesToFetch(SearchQuery query) {
        UidCriterion uidCrit = findConjugatedUidCriterion(query.getCriteria());
        if (uidCrit == null) {
            return List.of(MessageRange.all());
        }
        return Arrays.stream(uidCrit.getOperator().getRange())
            .map(range -> MessageRange.range(range.getLowValue(), range.getHighValue()))
            .toList();
    }

    private static void forEachMessage(MessageMapper mapper, Mailbox mailbox, List<MessageRange> ranges, FetchType fetchType,
                                       Consumer<MailboxMessage> consumer) throws MailboxException {
        for (MessageRange range : ranges) {
            mapper.findInMailbox(mailbox, range, fetchType, UNLIMITED).forEachRemaining(consumer);
        }
    }

    @Override
    public Flux<MessageId> search(MailboxSession session, final Collection<MailboxId> mailboxIds, SearchQuery searchQuery, SearchOptions searchOptions) throws MailboxException {
        MailboxMapper mailboxMapper = mailboxMapperFactory.getMailboxMapper(session);

        Flux<Mailbox> filteredMailboxes =
            Flux.fromIterable(mailboxIds)
            .concatMap(mailboxMapper::findMailboxById);

        return getAsMessageIds(searchResults(session, filteredMailboxes, searchQuery), searchOptions);
    }

    private Flux<? extends SearchResult> searchResults(MailboxSession session, Flux<Mailbox> mailboxes, SearchQuery query) {
        return mailboxes.concatMap(mailbox -> Mono.fromCallable(() -> getSearchResultStream(session, query, mailbox))
                .flatMapMany(Flux::fromStream)
                .subscribeOn(ReactorUtils.BLOCKING_CALL_WRAPPER))
            .collectList()
            .flatMapMany(hits -> Flux.fromStream(MessageSearches.order(hits, query)))
            .subscribeOn(ReactorUtils.BLOCKING_CALL_WRAPPER);
    }

    private Stream<MailboxMessage> getSearchResultStream(MailboxSession session, SearchQuery query, Mailbox mailbox) {
        try {
            return searchResults(session, mailbox, query).stream();
        } catch (MailboxException e) {
            throw new RuntimeException(e);
        }
    }

    private Flux<MessageId> getAsMessageIds(Flux<? extends SearchResult> temp, SearchOptions searchOptions) {
        return temp.map(searchResult -> searchResult.getMessageId().get())
            .filter(SearchUtil.distinct())
            .skip(searchOptions.offset().getOffset())
            .transform(searchOptions.limit()::applyOnFlux);
    }

}
