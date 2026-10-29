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

package org.apache.james.imap.processor.base;

import static io.vavr.API.$;
import static io.vavr.API.Case;
import static io.vavr.API.Match;
import static io.vavr.Predicates.instanceOf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

import jakarta.mail.Flags;
import jakarta.mail.Flags.Flag;

import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventListener;
import org.apache.james.events.Registration;
import org.apache.james.imap.api.process.SelectedMailbox;
import org.apache.james.mailbox.FlagsBuilder;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.NullableMessageSequenceNumber;
import org.apache.james.mailbox.events.MailboxEvents.Added;
import org.apache.james.mailbox.events.MailboxEvents.Expunged;
import org.apache.james.mailbox.events.MailboxEvents.FlagsUpdated;
import org.apache.james.mailbox.events.MailboxEvents.MailboxDeletion;
import org.apache.james.mailbox.events.MailboxEvents.MailboxEvent;
import org.apache.james.mailbox.events.MailboxEvents.MessageEvent;
import org.apache.james.mailbox.events.MailboxIdRegistrationKey;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.reactivestreams.Publisher;

import com.github.fge.lambdas.Throwing;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of {@link SelectedMailbox}
 */
public class SelectedMailboxImpl implements SelectedMailbox, EventListener.ReactiveEventListener {
    private static final Void VOID = null;
    private static final Flag UNINTERESTING_FLAGS = Flag.RECENT;

    @VisibleForTesting
    static class ApplicableFlags {
        static ApplicableFlags from(Flags flags) {
            return new ApplicableFlags(flags, flags);
        }

        private final Flags flags;
        private final Flags announced;

        private ApplicableFlags(Flags flags, Flags announced) {
            this.flags = flags;
            this.announced = announced;
        }

        public ApplicableFlags announce() {
            return new ApplicableFlags(flags, flags);
        }

        public Flags flags() {
            return new Flags(flags);
        }

        public Flags announced() {
            return new Flags(announced);
        }

        public boolean updated() {
            return !Arrays.asList(announced.getUserFlags()).containsAll(Arrays.asList(flags.getUserFlags()));
        }

        public ApplicableFlags updateWithNewFlags(Flags newFlags) {
            Flags updatedFlags = flags();
            updatedFlags.add(newFlags);
            // \RECENT is not a applicable flag in imap so remove it
            // from the list
            updatedFlags.remove(Flag.RECENT);
            return new ApplicableFlags(updatedFlags, announced);
        }
    }

    private final AtomicReference<Registration> registration = new AtomicReference<>();
    private final MailboxManager mailboxManager;
    private final MessageManager messageManager;
    private final MailboxId mailboxId;
    private final EventBus eventBus;
    private final MailboxSession mailboxSession;
    private final UidMsnConverter uidMsnConverter;
    private final Set<MessageUid> recentUids = new TreeSet<>();
    private final Set<MessageUid> addedUids = new TreeSet<>();
    private final Set<MessageUid> flagUpdateUids = new TreeSet<>();
    private final Set<MessageUid> expungedUids = new TreeSet<>();
    private final StampedLock applicableFlagsLock = new StampedLock();
    private final AtomicReference<ReactiveEventListener> idleEventListener = new AtomicReference<>();
    private final AtomicBoolean recentUidRemoved = new AtomicBoolean(false);
    private final AtomicBoolean isDeletedByOtherSession = new AtomicBoolean(false);
    private final AtomicBoolean silentFlagChanges = new AtomicBoolean(false);
    private final AtomicBoolean readOnly = new AtomicBoolean(false);
    private volatile ApplicableFlags applicableFlags = ApplicableFlags.from(new Flags());

    public SelectedMailboxImpl(MailboxManager mailboxManager, EventBus eventBus, MailboxSession session, MessageManager messageManager) {
        this.eventBus = eventBus;
        this.mailboxManager = mailboxManager;
        this.messageManager = messageManager;
        this.mailboxSession = session;
        this.uidMsnConverter = new UidMsnConverter();
        this.mailboxId = messageManager.getId();
    }

    public Mono<Void> finishInit() throws MailboxException {
        // Ignore events from our session
        setSilentFlagChanges(true);

        return Mono.from(eventBus.register(this, new MailboxIdRegistrationKey(mailboxId)))
                .doOnNext(this.registration::set)
            .then(messageManager.getApplicableFlagsReactive(mailboxSession)
                .doOnNext(flags -> {
                    long stamp = applicableFlagsLock.writeLock();
                    applicableFlags = applicableFlags.updateWithNewFlags(flags);
                    applicableFlagsLock.unlockWrite(stamp);
                }))
            .then(Flux.from(messageManager.search(SearchQuery.of(SearchQuery.all()), mailboxSession))
                .collect(ImmutableList.toImmutableList())
                .doOnNext(this::addInitialUids))
            .then();
    }

    private synchronized void addInitialUids(List<MessageUid> uids) {
        uidMsnConverter.addAll(uids);
        applyAddedUids();
    }

    @Override
    public void registerIdle(ReactiveEventListener idle) {
        idleEventListener.set(idle);
    }

    @Override
    public void unregisterIdle() {
        idleEventListener.set(null);
    }

    @Override
    public void unregisterIdle(ReactiveEventListener listener) {
        idleEventListener.compareAndSet(listener, null);
    }

    @Override
    public boolean isIdling() {
        return idleEventListener.get() != null;
    }

    @Override
    public Optional<MessageUid> getFirstUid() {
        return uidMsnConverter.getFirstUid();
    }

    @Override
    public Optional<MessageUid> getLastUid() {
        return uidMsnConverter.getLastUid();
    }

    @Override
    public List<MessageUid> allUids() {
        return uidMsnConverter.allUids();
    }

    @Override
    public Mono<Void> deselect() {
        return Mono.from(
            Optional.ofNullable(registration.get())
                .map(Registration::unregister)
                .orElse(Mono.empty()))
            .then(Mono.fromRunnable(this::clearInternalStructures)
                .subscribeOn(Schedulers.boundedElastic()))
            .then();
    }

    private synchronized void clearInternalStructures() {
        idleEventListener.set(null);
        uidMsnConverter.clear();
        addedUids.clear();
        flagUpdateUids.clear();

        expungedUids.clear();
        recentUids.clear();
    }

    @Override
    public MessageManager getMessageManager() {
        return messageManager;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly.get();
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.readOnly.set(readOnly);
    }

    @Override
    public synchronized boolean removeRecent(MessageUid uid) {
        final boolean result = recentUids.remove(uid);
        if (result) {
            recentUidRemoved.set(true);
        }
        return result;
    }

    @Override
    public synchronized boolean addRecent(MessageUid uid) {
        return recentUids.add(uid);
    }

    @Override
    public synchronized Collection<MessageUid> getRecent() {
        return new ArrayList<>(knownRecentUids());
    }

    @Override
    public synchronized int recentCount() {
        return knownRecentUids().size();
    }

    // An APPEND or a \Recent flag update can name a UID before its EXISTS response, or after its EXPUNGE one.
    private List<MessageUid> knownRecentUids() {
        return recentUids.stream()
            .filter(uid -> uidMsnConverter.getMsn(uid).foldSilent(() -> false, msn -> true))
            .collect(ImmutableList.toImmutableList());
    }

    @Override
    public Mono<MailboxPath> getPathReactive() {
        return Mono.from(mailboxManager.getMailboxReactive(mailboxId, mailboxSession))
            .map(Throwing.function(MessageManager::getMailboxPath));
    }

    @Override
    public MailboxId getMailboxId() {
        return mailboxId;
    }

    @Override
    public synchronized boolean isRecent(MessageUid uid) {
        return recentUids.contains(uid);
    }

    @Override
    public boolean isRecentUidRemoved() {
        return recentUidRemoved.get();
    }

    @Override
    public void resetRecentUidRemoved() {
        recentUidRemoved.set(false);
    }

    @Override
    public synchronized void resetEvents() {
        isDeletedByOtherSession.set(false);
    }

    @Override
    public synchronized boolean applyAddedUids() {
        if (addedUids.isEmpty()) {
            return false;
        }
        uidMsnConverter.addAll(addedUids);
        recentUids.addAll(addedUids);
        addedUids.clear();
        return true;
    }

    @Override
    public synchronized NullableMessageSequenceNumber remove(MessageUid uid) {
        recentUids.remove(uid);
        return uidMsnConverter.getAndRemove(uid);
    }

    private boolean interestingFlags(UpdatedFlags updated) {
        boolean result;
        final Iterator<Flags.Flag> it = updated.modifiedSystemFlags().iterator();
        if (it.hasNext()) {
            final Flags.Flag flag = it.next();
            result = !flag.equals(UNINTERESTING_FLAGS);
        } else {
            result = false;
        }
        // See if we need to check the user flags
        if (!result) {
            final Iterator<String> userIt = updated.userFlagIterator();
            result = userIt.hasNext();
        }
        return result;
    }
    
    @Override
    public synchronized void resetExpungedUids(Collection<MessageUid> sentUids) {
        expungedUids.removeAll(sentUids);
    }

    /**
     * Sets whether changes from current session should be ignored.
     * 
     * @param silentFlagChanges
     *            true if any flag changes from current session should be
     *            ignored, false otherwise
     */
    public final void setSilentFlagChanges(boolean silentFlagChanges) {
        this.silentFlagChanges.set(silentFlagChanges);
    }

    /**
     * Is the mailbox deleted?
     * 
     * @return true when the mailbox has been deleted by another session, false
     *         otherwise
     */

    @Override
    public final boolean isDeletedByOtherSession() {
        return isDeletedByOtherSession.get();
    }

    /**
     * Remove and return the uids which have updated flags since the last call
     */
    @Override
    public synchronized Collection<MessageUid> takeFlagUpdateUids() {
        // copy the TreeSet to fix possible
        // java.util.ConcurrentModificationException
        // See IMAP-278
        Collection<MessageUid> uids = ImmutableSortedSet.copyOf(flagUpdateUids);
        flagUpdateUids.clear();
        return uids;
    }

    @Override
    public synchronized Collection<MessageUid> expungedUids() {
        // copy the TreeSet to fix possible
        // java.util.ConcurrentModificationException
        // See IMAP-278
        return ImmutableSortedSet.copyOf(expungedUids);
    }

    @Override
    public Flags getApplicableFlags() {
        return applicableFlags.flags();
    }

    @Override
    public Flags getAnnouncedFlags() {
        return applicableFlags.announced();
    }

    @Override
    public Flags announceApplicableFlags() {
        long stamp = applicableFlagsLock.writeLock();
        try {
            applicableFlags = applicableFlags.announce();
            return applicableFlags.flags();
        } finally {
            applicableFlagsLock.unlockWrite(stamp);
        }
    }

    @Override
    public Optional<Flags> announceApplicableFlags(Flags messageFlags) {
        long stamp = applicableFlagsLock.writeLock();
        try {
            applicableFlags = applicableFlags.updateWithNewFlags(messageFlags);
            if (!applicableFlags.updated()) {
                return Optional.empty();
            }
            applicableFlags = applicableFlags.announce();
            return Optional.of(applicableFlags.flags());
        } finally {
            applicableFlagsLock.unlockWrite(stamp);
        }
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        return Mono.fromRunnable(() -> synchronizedEvent(event))
            .subscribeOn(Schedulers.boundedElastic())
            .then(Mono.fromCallable(idleEventListener::get)
                .flatMap(listener -> Mono.from(listener.reactiveEvent(event))));
    }

    private synchronized void synchronizedEvent(Event event) {
        if (event instanceof MailboxEvent) {
            MailboxEvent mailboxEvent = (MailboxEvent) event;
            mailboxEvent(mailboxEvent);
        }
    }

    private void mailboxEvent(MailboxEvent mailboxEvent) {
        // Check if the event was for the mailbox we are observing
        if (mailboxEvent.getMailboxId().equals(getMailboxId())) {
            Match(mailboxEvent).of(
                Case($(instanceOf(Added.class)),
                    this::handleAddition),
                Case($(instanceOf(FlagsUpdated.class)),
                    this::handleFlagsUpdates),
                Case($(instanceOf(Expunged.class)),
                    this::handleMailboxExpunge),
                Case($(instanceOf(MailboxDeletion.class)),
                    this::handleMailboxDeletion),
                Case($(), VOID)
            );
        }
    }

    private Void handleMailboxDeletion(MailboxDeletion mailboxDeletion) {
        if (isFromOtherSession(mailboxDeletion)) {
            isDeletedByOtherSession.set(true);
        }
        return VOID;
    }

    private boolean isFromOtherSession(MailboxEvent mailboxDeletion) {
        return mailboxDeletion.getSessionId() != mailboxSession.getSessionId();
    }

    private Void handleMailboxExpunge(MessageEvent messageEvent) {
        // A UID the client has no EXISTS for yet leaves without an EXPUNGE, whose sequence number it could not know
        messageEvent.getUids().stream()
            .filter(uid -> !addedUids.remove(uid))
            .forEach(expungedUids::add);
        return VOID;
    }

    private Void handleFlagsUpdates(FlagsUpdated updated) {
        List<UpdatedFlags> uFlags = updated.getUpdatedFlags();
        if (isFromOtherSession(updated) || !silentFlagChanges.get()) {

            for (UpdatedFlags u : uFlags) {
                if (interestingFlags(u)) {
                    flagUpdateUids.add(u.getUid());
                }
            }
        }

        // We need to add the UID of the message to the recent
        // list if we receive an flag update which contains a
        // \RECENT flag
        // See IMAP-287
        List<UpdatedFlags> uflags = updated.getUpdatedFlags();
        for (UpdatedFlags u : uflags) {
            if (u.modifiedSystemFlags().contains(Flag.RECENT)) {
                recentUids.add(u.getUid());
            }
        }
        long stamp = applicableFlagsLock.writeLock();
        applicableFlags = updateApplicableFlags(applicableFlags, updated);
        applicableFlagsLock.unlock(stamp);
        return VOID;
    }

    private Void handleAddition(Added added) {
        addedUids.addAll(added.getUids());
        long stamp = applicableFlagsLock.writeLock();
        applicableFlags = updateApplicableFlags(applicableFlags, added);
        applicableFlagsLock.unlockWrite(stamp);
        return VOID;
    }

    @VisibleForTesting
    static ApplicableFlags updateApplicableFlags(ApplicableFlags applicableFlags, FlagsUpdated flagsUpdated) {
        Flags updatedFlags = mergeAllNewFlags(flagsUpdated);
        return applicableFlags.updateWithNewFlags(updatedFlags);
    }

    @VisibleForTesting
    static ApplicableFlags updateApplicableFlags(ApplicableFlags applicableFlags, Added added) {
        Flags addedFlags = mergeAllNewFlags(added);
        return applicableFlags.updateWithNewFlags(addedFlags);
    }

    private static Flags mergeAllNewFlags(FlagsUpdated flagsUpdated) {
        List<UpdatedFlags> flags = flagsUpdated.getUpdatedFlags();
        FlagsBuilder builder = FlagsBuilder.builder();
        flags.stream().map(UpdatedFlags::getNewFlags).forEach(builder::add);
        return builder.build();
    }

    private static Flags mergeAllNewFlags(Added added) {
        FlagsBuilder builder = FlagsBuilder.builder();
        added.getAdded().values().stream()
            .map(MessageMetaData::getFlags)
            .forEach(builder::add);
        return builder.build();
    }

    @Override
    public NullableMessageSequenceNumber msn(MessageUid uid) {
        return uidMsnConverter.getMsn(uid);
    }

    @Override
    public Optional<MessageUid> uid(int msn) {
        if (msn == NO_SUCH_MESSAGE) {
            return Optional.empty();
        }

        return uidMsnConverter.getUid(msn);
    }

    
    @Override
    public long existsCount() {
        return uidMsnConverter.getNumMessage();
    }
}
