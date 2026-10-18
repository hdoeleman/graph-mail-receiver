package org.example.graphmail.sync;

import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.DeltaSource;
import org.example.graphmail.service.DeltaSource.DeltaPage;
import org.example.graphmail.service.DeltaTokenExpiredException;
import org.example.graphmail.service.DemoMailSource;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSourceException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.store.StoredEmail;
import org.example.graphmail.store.StoredEmailRepository;
import org.example.graphmail.store.SyncState;
import org.example.graphmail.store.SyncStateRepository;
import org.example.graphmail.sync.NewMailService.NewMails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The new-mail sync against the real Flyway schema in an embedded database, with a scripted {@link DeltaSource} in
 * place of Graph. Not transactional, so each page's commit is real.
 */
@SpringBootTest(properties = {
        "graph.tenant-id=t", "graph.client-id=c", "graph.client-secret=s", "graph.mailbox=user@test",
        "graph.initial-sync-window=7d"})
@AutoConfigureTestDatabase   // an embedded database, even if config/application.yml or the environment names another
class NewMailServiceTest {

    private static final String MAILBOX = "user@test";
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofDays(7));

    @MockitoBean
    MailSources sources;

    @MockitoBean
    Clock clock;

    @Autowired
    NewMailService service;

    @Autowired
    StoredEmailRepository emails;

    @Autowired
    SyncStateRepository syncStates;

    private final ScriptedDelta delta = new ScriptedDelta();

    @BeforeEach
    void setUp() {
        emails.deleteAll();
        syncStates.deleteAll();
        when(clock.instant()).thenReturn(NOW);
        when(sources.delta(MailMode.REST)).thenReturn(delta);
    }

    @AfterEach
    void restorePageCap() {
        service.maxPagesPerPull = NewMailService.MAX_PAGES_PER_PULL;
    }

    private static EmailMessage mail(String id, String receivedAt) {
        return new EmailMessage(id, "Ada " + id, id + "@example.com", "subject " + id, Instant.parse(receivedAt),
                "body " + id);
    }

    private static DeltaPage next(String nextLink, EmailMessage... mails) {
        return new DeltaPage(List.of(mails), nextLink, null);
    }

    private static DeltaPage last(String deltaLink, EmailMessage... mails) {
        return new DeltaPage(List.of(mails), null, deltaLink);
    }

    private void store(EmailMessage mail, String mailbox) {
        emails.save(StoredEmail.of(mail, mailbox, MailMode.REST, NOW));
    }

    private void completedSyncWithDeltaLink(String link) {
        syncStates.save(SyncState.empty(MAILBOX).completed(link, NOW));
    }

    private SyncState state() {
        return syncStates.findById(MAILBOX).orElseThrow();
    }

    @Test
    void firstPullLooksBackTheInitialWindowAndStoresEveryPage() {
        delta.onStart(() -> next("next-1", mail("a", "2026-10-17T10:00:00Z"), mail("b", "2026-10-18T09:00:00Z")));
        delta.onFollow("next-1", () -> last("delta-1", mail("c", "2026-10-16T08:00:00Z")));

        NewMails result = service.pullNew(MailMode.REST);

        assertThat(delta.calls).containsExactly("start " + WINDOW_START, "follow next-1");
        assertThat(result.emails()).containsExactly(          // newest first
                mail("b", "2026-10-18T09:00:00Z"), mail("a", "2026-10-17T10:00:00Z"), mail("c", "2026-10-16T08:00:00Z"));
        assertThat(result.stored()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.storedTotal()).isEqualTo(3);
        // the stored row holds the whole mail
        assertThat(emails.findById("b")).get()
                .satisfies(row -> assertThat(row.toEmailMessage()).isEqualTo(mail("b", "2026-10-18T09:00:00Z")))
                .extracting(StoredEmail::mailbox, StoredEmail::fetchedVia, StoredEmail::storedAt)
                .containsExactly(MAILBOX, "rest", NOW);
        assertThat(state()).extracting(SyncState::deltaLink, SyncState::nextLink, SyncState::syncSince)
                .containsExactly("delta-1", null, null);
    }

    @Test
    void nextPullFollowsTheDeltaLinkAndReturnsOnlyMailNotStoredBefore() {
        store(mail("a", "2026-10-17T10:00:00Z"), MAILBOX);
        completedSyncWithDeltaLink("delta-1");
        delta.onFollow("delta-1", () -> last("delta-2", mail("a", "2026-10-17T10:00:00Z"), mail("d", "2026-10-18T11:00:00Z")));

        NewMails result = service.pullNew(MailMode.REST);

        assertThat(delta.calls).containsExactly("follow delta-1");
        assertThat(result.emails()).containsExactly(mail("d", "2026-10-18T11:00:00Z"));
        assertThat(result.storedTotal()).isEqualTo(2);
        assertThat(state().deltaLink()).isEqualTo("delta-2");
    }

    @Test
    void expiredDeltaLinkOfACompletedSyncStartsAgainFromThisMailboxsNewestStoredMail() {
        store(mail("old", "2026-10-01T08:00:00Z"), MAILBOX);
        store(mail("newest", "2026-10-18T10:00:00Z"), MAILBOX);
        store(mail("other-mailbox", "2026-10-18T11:59:00Z"), "other@test");   // newer, but not this mailbox's
        completedSyncWithDeltaLink("delta-1");
        delta.onFollow("delta-1", () -> { throw new DeltaTokenExpiredException("410", null); });
        // "ge" returns the boundary mail again
        delta.onStart(() -> last("delta-2", mail("newest", "2026-10-18T10:00:00Z"), mail("f", "2026-10-18T11:00:00Z")));

        NewMails result = service.pullNew(MailMode.REST);

        assertThat(delta.calls).containsExactly("follow delta-1", "start 2026-10-18T10:00:00Z");
        assertThat(result.emails()).extracting(EmailMessage::id).containsExactly("f");
        assertThat(result.storedTotal()).isEqualTo(3);   // this mailbox's only
        assertThat(state().deltaLink()).isEqualTo("delta-2");
    }

    @Test
    void aFirstSyncFailingOnPage2ContinuesFromItsNextLink() {
        delta.onStart(() -> next("next-1", mail("late", "2026-10-18T11:00:00Z")));
        delta.onFollow("next-1", () -> { throw new MailSourceException("Graph is down"); });

        assertThatThrownBy(() -> service.pullNew(MailMode.REST)).hasMessage("Graph is down");
        assertThat(emails.existsById("late")).isTrue();   // page 1 was committed with the progress
        assertThat(state()).extracting(SyncState::deltaLink, SyncState::nextLink, SyncState::syncSince)
                .containsExactly(null, "next-1", WINDOW_START);

        // the next pull continues with page 2, so its older mail is not skipped
        delta.onFollow("next-1", () -> last("delta-1", mail("early", "2026-10-17T08:00:00Z")));
        NewMails result = service.pullNew(MailMode.REST);

        assertThat(delta.calls).containsExactly("start " + WINDOW_START, "follow next-1", "follow next-1");
        assertThat(result.emails()).extracting(EmailMessage::id).containsExactly("early");
        assertThat(state()).extracting(SyncState::deltaLink, SyncState::nextLink, SyncState::syncSince)
                .containsExactly("delta-1", null, null);
    }

    @Test
    void anExpiredNextLinkRestartsFromTheUnfinishedSyncsStartNotFromTheNewestStoredMail() {
        delta.onStart(() -> next("next-1", mail("late", "2026-10-18T11:00:00Z")));
        delta.onFollow("next-1", () -> { throw new MailSourceException("Graph is down"); });
        assertThatThrownBy(() -> service.pullNew(MailMode.REST)).hasMessage("Graph is down");

        delta.onFollow("next-1", () -> { throw new DeltaTokenExpiredException("410", null); });
        delta.onStart(() -> last("delta-1", mail("late", "2026-10-18T11:00:00Z"), mail("early", "2026-10-17T08:00:00Z")));
        NewMails result = service.pullNew(MailMode.REST);

        // not "start 2026-10-18T11:00:00Z" (the newest stored mail), which would skip "early" for good
        assertThat(delta.calls).endsWith("follow next-1", "start " + WINDOW_START);
        assertThat(result.emails()).extracting(EmailMessage::id).containsExactly("early");
    }

    @Test
    void anInterruptedIncrementalSyncRestartsFromWhereItStarted() {
        store(mail("a", "2026-10-18T10:00:00Z"), MAILBOX);
        completedSyncWithDeltaLink("delta-1");
        delta.onFollow("delta-1", () -> next("next-1", mail("b", "2026-10-18T11:00:00Z")));
        delta.onFollow("next-1", () -> { throw new MailSourceException("Graph is down"); });
        assertThatThrownBy(() -> service.pullNew(MailMode.REST)).hasMessage("Graph is down");
        assertThat(state().syncSince()).isEqualTo(Instant.parse("2026-10-18T10:00:00Z"));

        delta.onFollow("next-1", () -> { throw new DeltaTokenExpiredException("410", null); });
        delta.onStart(() -> last("delta-2", mail("b", "2026-10-18T11:00:00Z"), mail("c", "2026-10-18T10:30:00Z")));
        NewMails result = service.pullNew(MailMode.REST);

        // from where the interrupted sync began (a's time), not from b's, so c is not skipped
        assertThat(delta.calls).endsWith("start 2026-10-18T10:00:00Z");
        assertThat(result.emails()).extracting(EmailMessage::id).containsExactly("c");
    }

    @Test
    void aSecondExpiryInOnePullFailsAndKeepsNoLinks() {
        completedSyncWithDeltaLink("delta-1");
        delta.onFollow("delta-1", () -> { throw new DeltaTokenExpiredException("410", null); });
        delta.onStart(() -> { throw new DeltaTokenExpiredException("400 syncStateNotFound", null); });

        assertThatThrownBy(() -> service.pullNew(MailMode.REST))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("again right after a resync");
        assertThat(delta.calls).containsExactly("follow delta-1", "start " + WINDOW_START);
        // the expired link is gone, so the next pull starts a new sync instead of retrying it
        assertThat(state().linkToFollow()).isNull();
    }

    @Test
    void thePageCapStopsAPullAndTheNextPullContinues() {
        service.maxPagesPerPull = 2;
        delta.onStart(() -> next("next-1", mail("m1", "2026-10-18T09:00:00Z")));
        delta.onFollow("next-1", () -> next("next-2", mail("m2", "2026-10-18T08:00:00Z")));

        NewMails first = service.pullNew(MailMode.REST);

        assertThat(first.complete()).isFalse();
        assertThat(first.emails()).extracting(EmailMessage::id).containsExactly("m1", "m2");
        assertThat(state().nextLink()).isEqualTo("next-2");

        delta.onFollow("next-2", () -> last("delta-1", mail("m3", "2026-10-18T07:00:00Z")));
        NewMails second = service.pullNew(MailMode.REST);

        assertThat(second.complete()).isTrue();
        assertThat(second.emails()).extracting(EmailMessage::id).containsExactly("m3");
        assertThat(delta.calls).containsExactly("start " + WINDOW_START, "follow next-1", "follow next-2");
    }

    @Test
    void aPageThatFailsToStoreIsRolledBackWithItsProgress() {
        completedSyncWithDeltaLink("delta-1");
        // the new deltaLink is longer than the column allows, so saving the progress fails after the mail was saved
        delta.onFollow("delta-1", () -> last("x".repeat(8001), mail("z", "2026-10-18T11:00:00Z")));

        assertThatThrownBy(() -> service.pullNew(MailMode.REST)).hasStackTraceContaining("too long");

        assertThat(emails.existsById("z")).isFalse();             // rolled back together
        assertThat(state().deltaLink()).isEqualTo("delta-1");    // the previous progress stands
    }

    @Test
    void resetKeepsAnUnfinishedSyncsStartPoint() {
        delta.onStart(() -> next("next-1", mail("late", "2026-10-18T11:00:00Z")));
        delta.onFollow("next-1", () -> { throw new MailSourceException("Graph is down"); });
        assertThatThrownBy(() -> service.pullNew(MailMode.REST)).hasMessage("Graph is down");

        service.resetSync();
        delta.onStart(() -> last("delta-1"));
        service.pullNew(MailMode.REST);

        assertThat(delta.calls).endsWith("start " + WINDOW_START);
    }

    @Test
    void resetAfterACompletedSyncStartsAgainFromTheNewestStoredMail() {
        store(mail("newest", "2026-10-18T10:00:00Z"), MAILBOX);
        completedSyncWithDeltaLink("delta-1");

        service.resetSync();
        delta.onStart(() -> last("delta-2"));
        service.pullNew(MailMode.REST);

        assertThat(delta.calls).containsExactly("start 2026-10-18T10:00:00Z");
    }

    @Test
    void entriesWithoutIdOrReceivedDateAreSkipped() {
        delta.onStart(() -> last("delta-1",
                new EmailMessage(null, null, null, null, Instant.parse("2026-10-18T10:00:00Z"), ""),
                new EmailMessage("no-date", null, null, null, null, ""),
                mail("ok", "2026-10-18T10:00:00Z")));

        assertThat(service.pullNew(MailMode.REST).emails()).extracting(EmailMessage::id).containsExactly("ok");
        assertThat(emails.count()).isEqualTo(1);
    }

    @Test
    void aPageWithNeitherLinkFails() {
        delta.onStart(() -> new DeltaPage(List.of(), null, null));

        assertThatThrownBy(() -> service.pullNew(MailMode.REST))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("neither a nextLink nor a deltaLink");
    }

    @Test
    void storedReturnsTheConfiguredMailboxsMailNewestFirst() {
        store(mail("a", "2026-10-17T10:00:00Z"), MAILBOX);
        store(mail("b", "2026-10-18T10:00:00Z"), MAILBOX);
        store(mail("other", "2026-10-18T11:00:00Z"), "other@test");

        assertThat(service.stored(10)).containsExactly(mail("b", "2026-10-18T10:00:00Z"), mail("a", "2026-10-17T10:00:00Z"));
        assertThat(service.stored(1)).extracting(EmailMessage::id).containsExactly("b");
    }

    @Test
    void demoModeReturnsTheSimulatedMailAndStoresNothing() {
        var demo = mock(DemoMailSource.class);
        when(demo.fetchNewSimulated()).thenReturn(List.of(mail("d1", "2026-10-18T11:59:00Z")));
        when(sources.demo()).thenReturn(demo);

        NewMails result = service.pullNew(MailMode.DEMO);

        assertThat(result.emails()).extracting(EmailMessage::id).containsExactly("d1");
        assertThat(result.stored()).isFalse();
        assertThat(emails.count()).isZero();
        assertThat(syncStates.count()).isZero();
        assertThat(delta.calls).isEmpty();
    }

    @Test
    void pullsAreSerialized() throws Exception {
        completedSyncWithDeltaLink("delta-1");
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        delta.onFollow("delta-1", () -> {
            firstEntered.countDown();
            await(releaseFirst);
            return last("delta-2", mail("x", "2026-10-18T11:00:00Z"));
        });
        delta.onFollow("delta-2", () -> last("delta-3", mail("x", "2026-10-18T11:00:00Z")));
        var errors = new CopyOnWriteArrayList<Throwable>();

        Thread first = Thread.ofPlatform().start(() -> pullCatching(errors));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofPlatform().start(() -> pullCatching(errors));
        Thread.sleep(300);
        assertThat(delta.calls).containsExactly("follow delta-1");   // the second pull waits for the first
        releaseFirst.countDown();
        first.join(5_000);
        second.join(5_000);

        assertThat(errors).isEmpty();
        assertThat(delta.calls).containsExactly("follow delta-1", "follow delta-2");
        assertThat(emails.count()).isEqualTo(1);
    }

    private void pullCatching(List<Throwable> errors) {
        try {
            service.pullNew(MailMode.REST);
        } catch (Throwable t) {
            errors.add(t);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Answers start/follow calls from a script, and records them. */
    static final class ScriptedDelta implements DeltaSource {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Deque<Supplier<DeltaPage>> starts = new ArrayDeque<>();
        private final Map<String, Deque<Supplier<DeltaPage>>> follows = new HashMap<>();

        synchronized void onStart(Supplier<DeltaPage> reply) {
            starts.add(reply);
        }

        synchronized void onFollow(String link, Supplier<DeltaPage> reply) {
            follows.computeIfAbsent(link, l -> new ArrayDeque<>()).add(reply);
        }

        @Override
        public MailMode mode() {
            return MailMode.REST;
        }

        @Override
        public DeltaPage start(Instant since) {
            calls.add("start " + since);
            Supplier<DeltaPage> reply;
            synchronized (this) {
                reply = starts.remove();
            }
            return reply.get();
        }

        @Override
        public DeltaPage follow(String link) {
            calls.add("follow " + link);
            Supplier<DeltaPage> reply;
            synchronized (this) {
                reply = follows.get(link).remove();
            }
            return reply.get();
        }
    }
}
