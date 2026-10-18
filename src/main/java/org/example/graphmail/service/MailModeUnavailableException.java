package org.example.graphmail.service;

/** Thrown when a pull asks for a mode that cannot be used, e.g. because its Graph settings are missing. */
public class MailModeUnavailableException extends RuntimeException {

    public MailModeUnavailableException(MailMode mode, String reason) {
        super("Mode " + mode.id() + " is not available: " + reason);
    }
}
