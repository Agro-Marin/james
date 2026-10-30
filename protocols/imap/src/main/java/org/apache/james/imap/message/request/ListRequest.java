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
package org.apache.james.imap.message.request;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.apache.james.imap.api.ImapCommand;
import org.apache.james.imap.api.ImapConstants;
import org.apache.james.imap.api.Tag;
import org.apache.james.imap.api.message.StatusDataItems;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

public class ListRequest extends AbstractImapRequest {

    // https://www.rfc-editor.org/rfc/rfc5258.html
    public enum ListSelectOption {
        REMOTE,
        RECURSIVEMATCH,
        SUBSCRIBED,
        // https://www.rfc-editor.org/rfc/rfc6154.html
        SPECIAL_USE
    }

    // https://www.rfc-editor.org/rfc/rfc5258.html
    public enum ListReturnOption {
        CHILDREN,
        SUBSCRIBED,
        // https://www.rfc-editor.org/rfc/rfc5819.html LIST STATUS
        STATUS,
        // https://www.rfc-editor.org/rfc/rfc8440.html
        MYRIGHTS,
        // https://www.rfc-editor.org/rfc/rfc6154.html
        SPECIAL_USE
    }

    private final String baseReferenceName;
    private final ImmutableList<String> mailboxPatterns;
    private final boolean extended;
    private final EnumSet<ListSelectOption> selectOptions;
    private final EnumSet<ListReturnOption> returnOptions;
    private final Optional<StatusDataItems> statusDataItems;

    public ListRequest(String referenceName, String mailboxPattern, Tag tag) {
        this(ImapConstants.LIST_COMMAND, referenceName, mailboxPattern, tag);
    }

    protected ListRequest(ImapCommand imapCommand, String referenceName, String mailboxPattern, Tag tag) {
        this(imapCommand, referenceName, ImmutableList.of(mailboxPattern), false, tag,
            EnumSet.noneOf(ListSelectOption.class), EnumSet.noneOf(ListReturnOption.class), Optional.empty());
    }

    /**
     * An extended LIST (RFC 5258): selection options, return options or a parenthesized list of patterns.
     */
    public ListRequest(ImapCommand imapCommand, String referenceName, List<String> mailboxPatterns, Tag tag,
                       EnumSet<ListSelectOption> selectOptions, EnumSet<ListReturnOption> returnOptions, Optional<StatusDataItems> statusDataItems) {
        this(imapCommand, referenceName, mailboxPatterns, true, tag, selectOptions, returnOptions, statusDataItems);
    }

    private ListRequest(ImapCommand imapCommand, String referenceName, List<String> mailboxPatterns, boolean extended, Tag tag,
                        EnumSet<ListSelectOption> selectOptions, EnumSet<ListReturnOption> returnOptions, Optional<StatusDataItems> statusDataItems) {
        super(tag, imapCommand);
        this.baseReferenceName = referenceName;
        this.mailboxPatterns = ImmutableList.copyOf(mailboxPatterns);
        this.extended = extended;
        this.selectOptions = selectOptions;
        this.returnOptions = returnOptions;
        this.statusDataItems = statusDataItems;
    }

    /**
     * The reference name, modified UTF-7 encoded.
     */
    public final String getBaseReferenceName() {
        return baseReferenceName;
    }

    /**
     * The mailbox patterns, modified UTF-7 encoded. A pattern that is not valid modified UTF-7 matches no
     * mailbox name.
     */
    public final ImmutableList<String> getMailboxPatterns() {
        return mailboxPatterns;
    }

    /**
     * RFC 3501's request for the hierarchy delimiter: a LIST with the empty pattern. RFC 5258 3: an extended
     * LIST never is one.
     */
    public boolean isHierarchyDelimiterRequest() {
        return !extended && mailboxPatterns.equals(ImmutableList.of(""));
    }

    public EnumSet<ListSelectOption> getSelectOptions() {
        return selectOptions;
    }

    public EnumSet<ListReturnOption> getReturnOptions() {
        return returnOptions;
    }

    public boolean selectSubscribed() {
        return getSelectOptions().contains(ListSelectOption.SUBSCRIBED);
    }

    public boolean selectRemote() {
        return getSelectOptions().contains(ListSelectOption.REMOTE);
    }

    public Optional<StatusDataItems> getStatusDataItems() {
        return statusDataItems;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("baseReferenceName", baseReferenceName)
            .add("mailboxPatterns", mailboxPatterns)
            .add("selectOptions", selectOptions)
            .add("returnOptions", returnOptions)
            .add("statusDataItem", statusDataItems.orElse(null))
            .toString();
    }
}
