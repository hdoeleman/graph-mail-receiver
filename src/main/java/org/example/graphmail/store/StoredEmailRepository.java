package org.example.graphmail.store;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface StoredEmailRepository extends ListCrudRepository<StoredEmail, String> {

    List<StoredEmail> findByMailboxOrderByReceivedAtDesc(String mailbox, Limit limit);

    /** The newest stored mail, where a resync starts. */
    Optional<StoredEmail> findFirstByMailboxOrderByReceivedAtDesc(String mailbox);

    long countByMailbox(String mailbox);
}
