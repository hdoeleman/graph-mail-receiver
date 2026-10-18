package org.example.graphmail.sync;

import org.example.graphmail.config.GraphProperties;
import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.DeltaSource;
import org.example.graphmail.service.DeltaSource.DeltaPage;
import org.example.graphmail.service.DeltaTokenExpiredException;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSourceException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.store.StoredEmail;
import org.example.graphmail.store.StoredEmailRepository;
import org.example.graphmail.store.SyncState;
import org.example.graphmail.store.SyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "New mails only" pulls: a Graph delta sync of the Inbox whose results are stored in the database.
 * <ul>
 *   <li>A mail counts as new when no earlier new-mail pull stored it, whichever mode fetched it. The (immutable)
 *       Graph message id is the key, so a mail delivered twice (a repeated page, the boundary of a resync) is stored
 *       once.</li>
 *   <li>Every page is saved in one transaction with the sync's progress: the next page's link while the sync is
 *       unfinished, the deltaLink once it completes. A pull that stops part-way (a failure, or the page cap) leaves
 *       the next page's link, and the next pull continues there.</li>
 *   <li>Each sync records where it started (the newest stored mail, or {@code graph.initial-sync-window} ago when
 *       nothing is stored) until it completes. When Graph rejects a link (410, or a 4xx such as
 *       {@code syncStateNotFound}), the sync starts again from that point, so pages an unfinished sync hadn't reached
 *       are not skipped and mail older than that point is not fetched again. The restart link Graph may offer is not
 *       used: it repeats the very first, unbounded query.</li>
 *   <li>A new sync's first query has a {@code receivedDateTime} filter, for which Graph returns at most
 *       {@value #FILTERED_DELTA_CAP} messages, newest first; a round reaching that is logged as a warning.</li>
 * </ul>
 * One sync state per mailbox is shared by the rest and sdk modes: Graph's links are plain URLs either can follow.
 * Pulls are serialized, so two clicks can't run the same sync twice at once.
 */
@Service
public class NewMailService {

    /** Pages per pull (at 50 mails a page, about 10,000 mails); a bigger sync continues on the next pull. */
    static final int MAX_PAGES_PER_PULL = 200;

    /** {@link #MAX_PAGES_PER_PULL}, lowered by tests. */
    int maxPagesPerPull = MAX_PAGES_PER_PULL;

    /** Graph's limit for a delta query with a {@code $filter}. */
    static final int FILTERED_DELTA_CAP = 5_000;

    private static final Logger log = LoggerFactory.getLogger(NewMailService.class);

    private final MailSources sources;
    private final GraphProperties props;
    private final StoredEmailRepository emails;
    private final SyncStateRepository syncStates;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public NewMailService(MailSources sources, GraphProperties props, StoredEmailRepository emails,
                          SyncStateRepository syncStates, TransactionTemplate transaction, Clock clock) {
        this.sources = sources;
        this.props = props;
        this.emails = emails;
        this.syncStates = syncStates;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * The result of a pull: the mails new to this app, newest first, how many are stored in total, and whether the
     * sync completed (false when the page cap stopped it; the next pull continues).
     */
    public record NewMails(List<EmailMessage> emails, boolean stored, long storedTotal, boolean complete) {
    }

    /**
     * Fetches the mails no earlier new-mail pull stored. Demo mode makes up a few and stores nothing.
     *
     * @throws org.example.graphmail.service.MailModeUnavailableException when the mode cannot be used
     * @throws MailSourceException                                          when Graph fails
     */
    public synchronized NewMails pullNew(MailMode mode) {
        if (mode == MailMode.DEMO) {
            return new NewMails(sources.demo().fetchNewSimulated(), false, 0, true);
        }
        DeltaSource source = sources.delta(mode);
        String mailbox = props.mailbox();
        SyncState state = syncStates.findById(mailbox).orElse(SyncState.empty(mailbox));
        // Where this sync started; fixed before anything is stored, and kept until the sync completes.
        Instant syncSince = state.syncSince() != null ? state.syncSince() : newestStoredOrWindowStart(mailbox);
        String link = state.linkToFollow();
        log.debug("{}: new-mail pull for {}, {}", mode.id(), mailbox, link == null ? "starting a new sync since " + syncSince
                : state.nextLink() != null ? "continuing an unfinished sync" : "continuing after the last sync");

        List<EmailMessage> fresh = new ArrayList<>();
        boolean restarted = false;
        boolean complete = false;
        int filteredRoundEntries = -1;   // entries since this pull's start() call, -1 when it didn't call it
        for (int pages = 0; pages < maxPagesPerPull; pages++) {
            DeltaPage page;
            try {
                if (link == null) {
                    page = source.start(syncSince);
                    filteredRoundEntries = 0;
                } else {
                    page = source.follow(link);
                }
            } catch (DeltaTokenExpiredException e) {
                if (restarted) {
                    throw new MailSourceException("Graph rejected the delta link again right after a resync", e);
                }
                restarted = true;
                log.info("{}: {}, starting the sync again since {}", mode.id(), e.getMessage(), syncSince);
                state = syncStates.save(state.withoutLinks(clock.instant()));
                link = null;
                continue;
            }
            PageResult result = store(state, mode, page, syncSince);
            state = result.state();
            fresh.addAll(result.stored());
            if (filteredRoundEntries >= 0) {
                filteredRoundEntries += page.emails().size();
            }
            if (page.deltaLink() != null) {
                complete = true;
                if (filteredRoundEntries >= FILTERED_DELTA_CAP) {
                    log.warn("{}: the sync since {} returned {} messages, Graph's limit for a filtered delta query; "
                            + "older mail in that range may be missing", mode.id(), syncSince, filteredRoundEntries);
                }
                break;
            }
            if (page.nextLink() == null) {
                throw new MailSourceException("Graph returned a delta page with neither a nextLink nor a deltaLink");
            }
            link = page.nextLink();
        }
        if (!complete) {
            log.info("{}: stopped after {} pages; the next pull continues the sync", mode.id(), maxPagesPerPull);
        }
        fresh.sort(Comparator.comparing(EmailMessage::receivedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        long total = emails.countByMailbox(mailbox);
        log.debug("{}: new-mail pull done: {} new, {} stored in total, sync {}", mode.id(), fresh.size(), total,
                complete ? "complete" : "continues");
        return new NewMails(fresh, true, total, complete);
    }

    /** The stored mails of the configured mailbox, newest first. */
    public List<EmailMessage> stored(int limit) {
        return emails.findByMailboxOrderByReceivedAtDesc(props.mailbox(), Limit.of(limit))
                .stream().map(StoredEmail::toEmailMessage).toList();
    }

    /**
     * Forgets the links; the next pull starts a new sync, from where an unfinished sync started or else from the
     * newest stored mail. Nothing is stored twice.
     */
    public synchronized void resetSync() {
        log.info("Delta sync reset for {}", props.mailbox());
        syncStates.findById(props.mailbox()).ifPresent(state -> syncStates.save(state.withoutLinks(clock.instant())));
    }

    private Instant newestStoredOrWindowStart(String mailbox) {
        return emails.findFirstByMailboxOrderByReceivedAtDesc(mailbox)
                .map(StoredEmail::receivedAt)
                .orElseGet(() -> clock.instant().minus(props.initialSyncWindow()));
    }

    private record PageResult(List<EmailMessage> stored, SyncState state) {
    }

    /** Stores the page's new mails and the sync's progress after it, in one transaction. */
    private PageResult store(SyncState state, MailMode mode, DeltaPage page, Instant syncSince) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            List<EmailMessage> stored = new ArrayList<>();
            for (EmailMessage email : page.emails()) {
                if (email.id() == null || email.receivedAt() == null) {
                    log.debug("{}: skipping a delta entry without id or receivedDateTime", mode.id());
                } else if (emails.existsById(email.id())) {
                    log.trace("{}: already stored: {}", mode.id(), email.id());
                } else {
                    emails.save(StoredEmail.of(email, state.mailbox(), mode, now));
                    stored.add(email);
                }
            }
            SyncState next = page.deltaLink() != null
                    ? state.completed(page.deltaLink(), now)
                    : state.inProgress(page.nextLink(), syncSince, now);
            log.debug("{}: stored {} of {} mails on this page", mode.id(), stored.size(), page.emails().size());
            return new PageResult(stored, syncStates.save(next));
        });
    }
}
