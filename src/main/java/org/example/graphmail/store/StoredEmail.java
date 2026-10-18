package org.example.graphmail.store;

import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.MailMode;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A mail stored by a "new mails only" pull. The Graph message id is the key; {@code version} is null for a mail not
 * stored yet, which makes Spring Data JDBC insert it.
 */
@Table("email")
public record StoredEmail(
        @Id String id,
        String mailbox,
        String senderName,
        String senderAddress,
        String subject,
        Instant receivedAt,
        String body,
        String fetchedVia,
        Instant storedAt,
        @Version Long version) {

    public static StoredEmail of(EmailMessage email, String mailbox, MailMode fetchedVia, Instant storedAt) {
        return new StoredEmail(email.id(), mailbox, email.senderName(), email.senderAddress(), email.subject(),
                email.receivedAt(), email.body(), fetchedVia.id(), storedAt, null);
    }

    public EmailMessage toEmailMessage() {
        return new EmailMessage(id, senderName, senderAddress, subject, receivedAt, body);
    }
}
