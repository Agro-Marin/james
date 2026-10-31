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

package mx.agromarin.james.odoo;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.protocols.smtp.SMTPRetCode;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.rrt.api.RecipientRewriteTable;
import org.apache.james.smtpserver.fastfail.ValidRcptHandler;
import org.apache.james.user.api.UsersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ValidRcptHandler} that also accepts the addresses Odoo answers. An address that is both an Odoo address and
 * a James user is a configuration error: it is deferred, not delivered to either, until one side drops it.
 */
public class OdooValidRcptHandler extends ValidRcptHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(OdooValidRcptHandler.class);

    private final UsersRepository users;
    private final OdooAddresses addresses;

    @Inject
    public OdooValidRcptHandler(UsersRepository users, RecipientRewriteTable recipientRewriteTable, DomainList domains,
                                OdooAddresses addresses) {
        super(users, recipientRewriteTable, domains);
        this.users = users;
        this.addresses = addresses;
    }

    @Override
    public HookResult doRcpt(SMTPSession session, MaybeSender sender, MailAddress rcpt) {
        OdooAddresses.Lookup lookup = addresses.lookup(rcpt);
        if (lookup == OdooAddresses.Lookup.NOT_ODOO) {
            return super.doRcpt(session, sender, rcpt);
        }
        try {
            if (!isLocalDomain(session, rcpt.getDomain())) {
                return super.doRcpt(session, sender, rcpt);
            }
            if (lookup == OdooAddresses.Lookup.ODOO) {
                if (users.contains(users.getUsername(rcpt))) {
                    LOGGER.error("{} is both an Odoo address and a James user: mail for it is deferred until one drops it",
                        rcpt.asString());
                    return deferred(DSNStatus.SYSTEM_CFG_ERROR, "Configuration error for " + rcpt.asString());
                }
                return HookResult.DECLINED;
            }
            if (isValidRecipient(session, rcpt)) {
                return HookResult.DECLINED;
            }
            return deferred(DSNStatus.NETWORK_DIR_SERVER, "Directory unavailable for " + rcpt.asString());
        } catch (Exception error) {
            LOGGER.error("Cannot validate {}", rcpt.asString(), error);
            return deferred(DSNStatus.UNDEFINED_STATUS, "Unexpected error for " + rcpt.asString());
        }
    }

    private static HookResult deferred(String status, String description) {
        return HookResult.builder()
            .hookReturnCode(HookReturnCode.denySoft())
            .smtpReturnCode(SMTPRetCode.LOCAL_ERROR)
            .smtpDescription(DSNStatus.getStatus(DSNStatus.TRANSIENT, status) + " " + description)
            .build();
    }
}
