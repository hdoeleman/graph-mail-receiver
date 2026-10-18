package org.example.graphmail.web;

import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailModeUnavailableException;
import org.example.graphmail.service.MailSourceException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.service.MailSources.ModeStatus;
import org.example.graphmail.sync.NewMailService;
import org.example.graphmail.sync.NewMailService.NewMails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Temporary API behind the temporary page; the mode is chosen per pull. */
@RestController
@RequestMapping("/api")
public class MailController {

    static final int MAX_LIMIT = 50;
    static final int MAX_STORED_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(MailController.class);

    private final MailSources mailSources;
    private final NewMailService newMail;

    public MailController(MailSources mailSources, NewMailService newMail) {
        this.mailSources = mailSources;
        this.newMail = newMail;
    }

    /** A "new mails only" pull: changes state (stores mail, advances the delta sync), hence POST. */
    @PostMapping("/emails/new")
    public NewMails newEmails(@RequestParam(required = false) String mode) {
        MailMode chosen = mode == null ? mailSources.defaultMode() : MailMode.parse(mode);
        log.debug("New-mail pull requested: mode={}", chosen.id());
        NewMails result = newMail.pullNew(chosen);
        log.debug("New-mail pull complete: mode={} {} new", chosen.id(), result.emails().size());
        return result;
    }

    /** The mails stored by new-mail pulls, newest first. */
    @GetMapping("/emails/stored")
    public List<EmailMessage> storedEmails(@RequestParam(defaultValue = "50") int limit) {
        return newMail.stored(Math.max(1, Math.min(limit, MAX_STORED_LIMIT)));
    }

    /** Forgets the delta sync's links; stored mail stays, and the next new-mail pull starts a new sync without storing anything twice. */
    @PostMapping("/sync/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetSync() {
        newMail.resetSync();
    }

    /** The modes the UI offers, which of them can be used, and the one to preselect. */
    public record Modes(String defaultMode, List<ModeStatus> modes) {
    }

    @GetMapping("/modes")
    public Modes modes() {
        return new Modes(mailSources.defaultMode().id(), mailSources.statuses());
    }

    /** The "pull": every call asks the chosen source for the newest emails. Without a mode, the default is used. */
    @GetMapping("/emails")
    public List<EmailMessage> emails(@RequestParam(required = false) String mode,
                                     @RequestParam(defaultValue = "10") int limit) {
        MailMode chosen = mode == null ? mailSources.defaultMode() : MailMode.parse(mode);
        int effective = Math.max(1, Math.min(limit, MAX_LIMIT));
        log.debug("Pull requested: mode={} limit={} (effective={})", chosen.id(), limit, effective);
        List<EmailMessage> emails = mailSources.get(chosen).fetchLatest(effective);
        log.debug("Pull complete: mode={} returning {} emails", chosen.id(), emails.size());
        return emails;
    }

    @ExceptionHandler(MailSourceException.class)
    public ProblemDetail onMailSourceFailure(MailSourceException e) {
        log.warn("Pull failed: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler({MailModeUnavailableException.class, IllegalArgumentException.class})
    public ProblemDetail onBadMode(RuntimeException e) {
        log.debug("Pull rejected: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
