package org.example.graphmail.service;

/**
 * Graph no longer accepts a delta sync's link: a {@code 410 Gone}, or a 4xx with an error code such as
 * {@code syncStateNotFound}. Outlook keeps delta tokens in a limited cache, so this is a normal event. The restart
 * link Graph may offer is ignored: it repeats the original, unbounded first-sync query.
 */
public class DeltaTokenExpiredException extends RuntimeException {

    public DeltaTokenExpiredException(String reason, Throwable cause) {
        super("Graph rejected the delta link (" + reason + ")", cause);
    }

    /** True for the responses Graph uses when a delta token is no longer valid. */
    public static boolean isExpiry(int status, String errorCode) {
        if (status == 410) {
            return true;
        }
        return status >= 400 && status < 500 && errorCode != null
                && (errorCode.equalsIgnoreCase("syncStateNotFound")
                || errorCode.equalsIgnoreCase("syncStateInvalid")
                || errorCode.equalsIgnoreCase("resyncRequired"));
    }
}
