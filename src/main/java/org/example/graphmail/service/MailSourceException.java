package org.example.graphmail.service;

/** Thrown when emails cannot be retrieved from the upstream source. */
public class MailSourceException extends RuntimeException {

    public MailSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    public MailSourceException(String message) {
        super(message);
    }
}
