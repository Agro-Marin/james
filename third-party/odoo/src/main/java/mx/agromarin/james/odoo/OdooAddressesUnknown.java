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

import java.util.Collection;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMatcher;

/**
 * Matches every recipient while Odoo's addresses were never read, so mail spooled before a restart waits for them
 * instead of being routed as if no address were Odoo's.
 */
public class OdooAddressesUnknown extends GenericMatcher {
    private final OdooAddresses addresses;

    @Inject
    public OdooAddressesUnknown(OdooAddresses addresses) {
        this.addresses = addresses;
    }

    @Override
    public Collection<MailAddress> match(Mail mail) {
        if (addresses.snapshot().isPresent()) {
            return List.of();
        }
        return mail.getRecipients();
    }
}
