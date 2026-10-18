package org.example.graphmail.service;

import org.example.graphmail.model.EmailMessage;

import java.time.Instant;
import java.util.List;

/**
 * A Graph delta query on the Inbox, limited to newly created messages ({@code changeType=created}). A sync starts
 * with {@link #start}, follows each page's {@code nextLink}, and ends with a {@code deltaLink}, which the next sync
 * follows to get only messages created since.
 */
public interface DeltaSource {

    MailMode mode();

    /** The first page of a new sync: new messages received at or after {@code since}. */
    DeltaPage start(Instant since);

    /**
     * The page behind a nextLink or deltaLink.
     *
     * @throws DeltaTokenExpiredException when Graph no longer accepts the link's token
     */
    DeltaPage follow(String link);

    /** One page: its new emails, and either a {@code nextLink} (more pages) or a {@code deltaLink} (sync done). */
    record DeltaPage(List<EmailMessage> emails, String nextLink, String deltaLink) {
    }
}
