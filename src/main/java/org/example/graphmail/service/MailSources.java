package org.example.graphmail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All mail sources this app can use, and why any mode is unavailable. The mode is chosen per pull, so the registry
 * holds no "current" mode, only the default the UI starts with.
 */
public class MailSources {

    private static final Logger log = LoggerFactory.getLogger(MailSources.class);

    /** One mode as the UI shows it; {@code reason} is set only when the mode is unavailable. */
    public record ModeStatus(String mode, boolean available, String reason) {
    }

    private final MailMode defaultMode;
    private final Map<MailMode, MailSource> sources = new EnumMap<>(MailMode.class);
    private final Map<MailMode, String> unavailable;

    public MailSources(MailMode defaultMode, Collection<? extends MailSource> sources, Map<MailMode, String> unavailable) {
        this.defaultMode = defaultMode;
        sources.forEach(source -> this.sources.put(source.mode(), source));
        this.unavailable = Map.copyOf(unavailable);
        log.info("Mail modes available: {}, default: {}", this.sources.keySet(), defaultMode.id());
    }

    public MailMode defaultMode() {
        return defaultMode;
    }

    /** @throws MailModeUnavailableException when the mode cannot be used, e.g. because Graph settings are missing */
    public MailSource get(MailMode mode) {
        MailSource source = sources.get(mode);
        if (source == null) {
            throw new MailModeUnavailableException(mode, unavailable.getOrDefault(mode, "not configured"));
        }
        return source;
    }

    /** The demo source, which is always available. */
    public DemoMailSource demo() {
        return (DemoMailSource) get(MailMode.DEMO);
    }

    /**
     * The delta sync of a Graph mode.
     *
     * @throws MailModeUnavailableException when the mode cannot be used
     * @throws IllegalArgumentException     for demo mode, which has no delta sync
     */
    public DeltaSource delta(MailMode mode) {
        if (get(mode) instanceof DeltaSource delta) {
            return delta;
        }
        throw new IllegalArgumentException("Mode " + mode.id() + " has no delta sync");
    }

    public List<ModeStatus> statuses() {
        return Arrays.stream(MailMode.values())
                .map(mode -> sources.containsKey(mode)
                        ? new ModeStatus(mode.id(), true, null)
                        : new ModeStatus(mode.id(), false, unavailable.getOrDefault(mode, "not configured")))
                .toList();
    }
}
