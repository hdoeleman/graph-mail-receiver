package org.example.graphmail.store;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Where a mailbox's delta sync continues.
 *
 * @param deltaLink where the next pull continues once a sync completed
 * @param nextLink  the next page of a sync that stopped part-way (a failure, or the page cap); followed first
 * @param syncSince the start point of a sync that hasn't completed yet: if its links expire, the sync restarts from
 *                  here, so the pages it hadn't reached are not skipped
 */
@Table("sync_state")
public record SyncState(@Id String mailbox, String deltaLink, String nextLink, Instant syncSince, Instant updatedAt,
                        @Version Long version) {

    public static SyncState empty(String mailbox) {
        return new SyncState(mailbox, null, null, null, null, null);
    }

    /** A sync completed: continue from its deltaLink, nothing is in progress. */
    public SyncState completed(String deltaLink, Instant now) {
        return new SyncState(mailbox, deltaLink, null, null, now, version);
    }

    /** A sync got one page further; {@code since} is its start point when it began without a link. */
    public SyncState inProgress(String nextLink, Instant since, Instant now) {
        return new SyncState(mailbox, deltaLink, nextLink, since, now, version);
    }

    /** The links are gone (expired or reset); an unfinished sync's start point is kept. */
    public SyncState withoutLinks(Instant now) {
        return new SyncState(mailbox, null, null, syncSince, now, version);
    }

    /** The link to follow next, if any: an unfinished sync's next page first, else the last deltaLink. */
    public String linkToFollow() {
        return nextLink != null ? nextLink : deltaLink;
    }
}
