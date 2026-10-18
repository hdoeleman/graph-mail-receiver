package org.example.graphmail.service;

import org.example.graphmail.model.EmailMessage;

import java.util.List;

public interface MailSource {

    /** The mode this source implements. */
    MailMode mode();

    /** Returns up to {@code limit} emails, newest first. */
    List<EmailMessage> fetchLatest(int limit);
}
