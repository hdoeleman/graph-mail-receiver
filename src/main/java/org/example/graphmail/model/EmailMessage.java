package org.example.graphmail.model;

import java.time.Instant;

/**
 * An email as shown in the UI. {@code body} is plain text.
 */
public record EmailMessage(
        String id,
        String senderName,
        String senderAddress,
        String subject,
        Instant receivedAt,
        String body) {
}
