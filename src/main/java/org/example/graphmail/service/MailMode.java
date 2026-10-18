package org.example.graphmail.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** How emails are fetched: generated demo data, Graph through Spring's {@code @HttpExchange}, or the Graph SDK. */
public enum MailMode {
    DEMO, REST, SDK;

    /** The lower-case name used in configuration, the API and the UI. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses an {@link #id()}, ignoring case and surrounding whitespace. */
    public static MailMode parse(String value) {
        String wanted = value == null ? "" : value.strip();
        return Arrays.stream(values())
                .filter(mode -> mode.id().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown mode '" + value + "', expected one of "
                        + Arrays.stream(values()).map(MailMode::id).collect(Collectors.joining(", "))));
    }
}
