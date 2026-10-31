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

package org.apache.james.modules.mailbox;

import org.apache.james.events.EventListener;
import org.apache.james.mailbox.postgres.search.PostgresSearchCandidates;
import org.apache.james.mailbox.postgres.search.PostgresSearchTextDAO;
import org.apache.james.mailbox.postgres.search.PostgresSearchTextIndexer;
import org.apache.james.mailbox.store.search.SearchCandidates;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;

/**
 * The scanning search over PostgreSQL, narrowed by the text index: each added message's text goes to
 * message_search_text, and a text criterion reads only the messages whose text holds it.
 */
public class PostgresSearchTextModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(PostgresSearchTextDAO.Factory.class).in(Scopes.SINGLETON);
        bind(PostgresSearchCandidates.class).in(Scopes.SINGLETON);
        bind(PostgresSearchTextIndexer.class).in(Scopes.SINGLETON);

        OptionalBinder.newOptionalBinder(binder(), SearchCandidates.class)
            .setBinding().to(PostgresSearchCandidates.class);
        Multibinder.newSetBinder(binder(), EventListener.ReactiveGroupEventListener.class)
            .addBinding().to(PostgresSearchTextIndexer.class);
    }
}
