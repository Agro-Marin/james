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

import static org.apache.james.mailbox.postgres.search.PostgresSearchTextDataDefinition.SearchTextTable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.SearchQuery.ConjunctionCriterion;
import org.apache.james.mailbox.model.SearchQuery.Criterion;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.store.search.SearchCandidates;
import org.apache.james.mailbox.store.search.SearchText;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Narrows a search by its text criteria: each is a LIKE on the column holding the strings its matcher compares, so the
 * candidates hold every match. An AND narrows by the criteria that can, an OR only when all its criteria can, and a NOT
 * never: the complement of a superset is no superset.
 */
public class PostgresSearchCandidates implements SearchCandidates {
    private final PostgresSearchTextDAO.Factory daoFactory;

    @Inject
    public PostgresSearchCandidates(PostgresSearchTextDAO.Factory daoFactory) {
        this.daoFactory = daoFactory;
    }

    @Override
    public Optional<Collection<MessageUid>> of(MailboxSession session, Mailbox mailbox, SearchQuery query) {
        return all(query.getCriteria())
            .map(condition -> daoFactory.create(session.getUser().getDomainPart())
                .candidates((PostgresMailboxId) mailbox.getMailboxId(), condition)
                .collectList()
                .block());
    }

    static Optional<Condition> all(List<Criterion> criteria) {
        List<Condition> conditions = criteria.stream()
            .map(PostgresSearchCandidates::narrowing)
            .flatMap(Optional::stream)
            .toList();
        if (conditions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DSL.and(conditions));
    }

    private static Optional<Condition> any(List<Criterion> criteria) {
        List<Optional<Condition>> conditions = criteria.stream()
            .map(PostgresSearchCandidates::narrowing)
            .toList();
        if (conditions.isEmpty() || conditions.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        return Optional.of(DSL.or(conditions.stream().map(Optional::get).toList()));
    }

    static Optional<Condition> narrowing(Criterion criterion) {
        if (criterion instanceof SearchQuery.TextCriterion text) {
            String value = text.getOperator().getValue();
            return Optional.of(switch (text.getType()) {
                case BODY -> contains(SearchTextTable.BODIES, SearchText.textNeedle(value));
                case FULL -> contains(SearchTextTable.BODIES, SearchText.textNeedle(value))
                    .or(contains(SearchTextTable.HEADERS, SearchText.textNeedle(value)));
                case ATTACHMENTS -> contains(SearchTextTable.ATTACHMENTS, value);
                case ATTACHMENT_FILE_NAME -> contains(SearchTextTable.ATTACHMENT_NAMES, value);
            });
        }
        if (criterion instanceof SearchQuery.SubjectCriterion subject) {
            return Optional.of(contains(SearchTextTable.HEADERS, SearchText.headerNeedle(subject.getSubject())));
        }
        if (criterion instanceof SearchQuery.HeaderCriterion header) {
            if (header.getOperator() instanceof SearchQuery.ContainsOperator contains) {
                return Optional.of(contains(SearchTextTable.HEADERS, SearchText.headerNeedle(contains.getValue())));
            }
            if (header.getOperator() instanceof SearchQuery.AddressOperator address) {
                return Optional.of(contains(SearchTextTable.HEADERS, SearchText.headerNeedle(address.getAddress())));
            }
            return Optional.empty();
        }
        if (criterion instanceof ConjunctionCriterion conjunction) {
            return switch (conjunction.getType()) {
                case AND -> all(conjunction.getCriteria());
                case OR -> any(conjunction.getCriteria());
                case NOR -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static Condition contains(Field<String> column, String needle) {
        return column.like("%" + escapeLike(needle) + "%", '\\');
    }

    private static String escapeLike(String needle) {
        return needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
