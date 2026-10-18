package org.example.graphmail.service;

import org.example.graphmail.model.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Demo mode: produces arbitrary, randomly generated emails instead of calling Graph.
 * Every call returns a fresh set, newest first.
 */
public class DemoMailSource implements MailSource {

    private static final Logger log = LoggerFactory.getLogger(DemoMailSource.class);

    static final List<String[]> SENDERS = List.of(
            new String[]{"Ada Lovelace", "ada@example.com"},
            new String[]{"Grace Hopper", "grace.hopper@example.org"},
            new String[]{"Alan Turing", "alan@example.net"},
            new String[]{"Linus Torvalds", "linus@example.com"},
            new String[]{"Margaret Hamilton", "margaret@example.org"},
            new String[]{"Edsger Dijkstra", "ewd@example.net"},
            new String[]{"Barbara Liskov", "barbara@example.com"});

    static final List<String> SUBJECTS = List.of(
            "Quarterly report", "Lunch on Friday?", "Re: deployment window", "Invoice 2026-0142",
            "Meeting notes", "Your ticket has been updated", "Welcome aboard", "Question about the API",
            "Holiday schedule", "Security notice", "Draft for review", "Weekly digest");

    static final List<String> SENTENCES = List.of(
            "Please find the details below.", "Let me know if that works for you.",
            "I will follow up tomorrow morning.", "Thanks for your quick reply.",
            "The numbers look better than last month.", "Can we move this to next week?",
            "Attached is the latest version.", "No action is needed on your side.",
            "I think we should discuss this in person.", "See you at the stand-up.");

    private final Random random;
    private final Clock clock;

    public DemoMailSource() {
        this(new Random(), Clock.systemUTC());
    }

    DemoMailSource(Random random, Clock clock) {
        this.random = random;
        this.clock = clock;
        log.info("Demo mode source ready: emails are randomly generated, Graph is not called");
    }

    @Override
    public MailMode mode() {
        return MailMode.DEMO;
    }

    @Override
    public synchronized List<EmailMessage> fetchLatest(int limit) {
        log.debug("Generating {} arbitrary demo emails", limit);
        Instant now = clock.instant();
        List<EmailMessage> emails = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            emails.add(generate(now.minus(Duration.ofMinutes(random.nextInt(3 * 24 * 60)))));
        }
        emails.sort(Comparator.comparing(EmailMessage::receivedAt).reversed());
        return emails;
    }

    /** Simulates a "new mails only" pull: 0 to 3 mails received in the last few minutes. Nothing is stored. */
    public synchronized List<EmailMessage> fetchNewSimulated() {
        int count = random.nextInt(4);
        log.debug("Simulating {} new demo emails", count);
        Instant now = clock.instant();
        List<EmailMessage> emails = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            emails.add(generate(now.minus(Duration.ofSeconds(random.nextInt(300)))));
        }
        emails.sort(Comparator.comparing(EmailMessage::receivedAt).reversed());
        return emails;
    }

    private EmailMessage generate(Instant received) {
        String[] sender = SENDERS.get(random.nextInt(SENDERS.size()));
        EmailMessage email = new EmailMessage(
                UUID.nameUUIDFromBytes(Long.toString(random.nextLong()).getBytes()).toString(),
                sender[0], sender[1],
                SUBJECTS.get(random.nextInt(SUBJECTS.size())),
                received,
                body(sender[0]));
        log.trace("Generated demo email id={} from={} subject={}", email.id(), email.senderAddress(), email.subject());
        return email;
    }

    private String body(String senderName) {
        int sentences = 2 + random.nextInt(4);
        var sb = new StringBuilder("Hi,\n\n");
        for (int i = 0; i < sentences; i++) {
            sb.append(SENTENCES.get(random.nextInt(SENTENCES.size()))).append(' ');
        }
        return sb.toString().trim() + "\n\nBest regards,\n" + senderName;
    }
}
