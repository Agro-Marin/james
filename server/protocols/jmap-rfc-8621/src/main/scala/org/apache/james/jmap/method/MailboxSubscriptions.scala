/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *  http://www.apache.org/licenses/LICENSE-2.0                  *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.method

import jakarta.inject.Inject
import org.apache.james.mailbox.MailboxManager.MailboxSearchFetchType
import org.apache.james.mailbox.model.MailboxPath
import org.apache.james.mailbox.model.search.{MailboxQuery, PrefixedWildcard}
import org.apache.james.mailbox.{MailboxManager, MailboxSession, SubscriptionManager}
import reactor.core.scala.publisher.SFlux

import scala.jdk.CollectionConverters._

/**
 * Mailbox/set keeps a subscribed mailbox's parents subscribed (JAMES-3927): subscribing a mailbox subscribes its
 * parents, unsubscribing one unsubscribes its children. It is JMAP's: IMAP's SUBSCRIBE and UNSUBSCRIBE act on the
 * one name they are given (RFC 3501 6.3.6, 6.3.7).
 */
class MailboxSubscriptions @Inject()(subscriptionManager: SubscriptionManager, mailboxManager: MailboxManager) {
  def subscribeWithParents(session: MailboxSession, path: MailboxPath): Unit = {
    subscriptionManager.subscribe(session, path)
    path.getParents(session.getPathDelimiter).asScala
      .foreach(parent => subscriptionManager.subscribe(session, parent))
  }

  def unsubscribeWithChildren(session: MailboxSession, path: MailboxPath): Unit = {
    subscriptionManager.unsubscribe(session, path)
    val children = MailboxQuery.builder()
      .userAndNamespaceFrom(path)
      .expression(new PrefixedWildcard(path.getName + session.getPathDelimiter))
      .build()
    SFlux(mailboxManager.search(children, MailboxSearchFetchType.Minimal, session))
      .map(_.getPath)
      .collectSeq()
      .block()
      .foreach(child => subscriptionManager.unsubscribe(session, child))
  }
}
