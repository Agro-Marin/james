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

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.user.api.model.User;
import org.apache.james.user.lib.UsersDAO;

/**
 * James's users are Odoo's mailboxes ({@code mail_mailbox}), and a password is one of the user's mail keys: a key
 * Odoo issued for the mail scope, checked against the hash Odoo stores. Odoo manages both, so this is read-only.
 */
public class OdooUsersDAO implements UsersDAO {
    private final OdooDirectory directory;

    @Inject
    public OdooUsersDAO(OdooDirectory directory) {
        this.directory = directory;
    }

    @Override
    public boolean getDefaultVirtualHostingValue() {
        return true;
    }

    @Override
    public Optional<OdooUser> getUserByName(Username name) {
        return directory.mailbox(name.asString())
            .map(keys -> new OdooUser(name, keys, directory));
    }

    @Override
    public boolean contains(Username name) {
        return directory.mailbox(name.asString()).isPresent();
    }

    @Override
    public int countUsers() {
        return directory.mailboxes().size();
    }

    @Override
    public Iterator<Username> list() {
        return directory.mailboxes().stream().map(Username::of).iterator();
    }

    @Override
    public void addUser(Username username, String password) throws UsersRepositoryException {
        throw readOnly();
    }

    @Override
    public void updateUser(User user) throws UsersRepositoryException {
        throw readOnly();
    }

    @Override
    public void removeUser(Username name) throws UsersRepositoryException {
        throw readOnly();
    }

    private static UsersRepositoryException readOnly() {
        return new UsersRepositoryException("James's users are Odoo's mailboxes: change them in Odoo");
    }

    public record OdooUser(Username name, List<OdooDirectory.MailKey> keys, OdooDirectory directory) implements User {
        @Override
        public Username getUserName() {
            return name;
        }

        @Override
        public boolean verifyPassword(String pass) {
            return keys.stream().anyMatch(key -> key.opens(pass, directory.now()));
        }

        @Override
        public boolean setPassword(String newPass) {
            return false;
        }
    }
}
