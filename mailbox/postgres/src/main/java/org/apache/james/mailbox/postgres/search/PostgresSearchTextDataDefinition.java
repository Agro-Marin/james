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

import java.util.UUID;

import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.backends.postgres.PostgresTable;
import org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public interface PostgresSearchTextDataDefinition {
    interface SearchTextTable {
        Table<Record> TABLE_NAME = DSL.table("message_search_text");
        Field<UUID> MESSAGE_ID = PostgresMessageDataDefinition.MESSAGE_ID;
        Field<String> HEADERS = DSL.field("headers", SQLDataType.CLOB.notNull());
        Field<String> BODIES = DSL.field("bodies", SQLDataType.CLOB.notNull());
        Field<String> ATTACHMENTS = DSL.field("attachments", SQLDataType.CLOB.notNull());
        Field<String> ATTACHMENT_NAMES = DSL.field("attachment_names", SQLDataType.CLOB.notNull());

        PostgresTable TABLE = PostgresTable.name(TABLE_NAME.getName())
            .createTableStep(((dsl, tableName) -> dsl.createTableIfNotExists(tableName)
                .column(MESSAGE_ID)
                .column(HEADERS)
                .column(BODIES)
                .column(ATTACHMENTS)
                .column(ATTACHMENT_NAMES)
                .constraint(DSL.primaryKey(MESSAGE_ID))
                .constraint(DSL.foreignKey(MESSAGE_ID).references(PostgresMessageDataDefinition.MessageTable.TABLE_NAME,
                    PostgresMessageDataDefinition.MessageTable.MESSAGE_ID).onDeleteCascade())
                .comment("The text a search compares, by message: a trigram index finds the candidates of a text criterion")))
            .supportsRowLevelSecurity()
            .addAdditionalAlterQueries(
                trigramIndex(HEADERS), trigramIndex(BODIES), trigramIndex(ATTACHMENTS), trigramIndex(ATTACHMENT_NAMES))
            .build();

        private static PostgresTable.AllCasesAdditionalAlterQuery trigramIndex(Field<String> column) {
            return new PostgresTable.AllCasesAdditionalAlterQuery("CREATE INDEX IF NOT EXISTS " + TABLE_NAME.getName() + "_"
                + column.getName() + "_trigram_index ON " + TABLE_NAME.getName() + " USING GIN (" + column.getName() + " gin_trgm_ops)");
        }
    }

    PostgresDataDefinition MODULE = PostgresDataDefinition.builder()
        .addTable(SearchTextTable.TABLE)
        .build();
}
