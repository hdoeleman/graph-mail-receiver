package org.example.graphmail.service;

import org.example.graphmail.model.EmailMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DemoMailSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void generatesRequestedNumberOfEmailsNewestFirst() {
        var source = new DemoMailSource(new Random(42), clock);

        List<EmailMessage> emails = source.fetchLatest(15);

        assertThat(emails).hasSize(15);
        assertThat(emails).extracting(EmailMessage::receivedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void generatedEmailsAreCompleteAndNotInTheFuture() {
        var source = new DemoMailSource(new Random(1), clock);

        for (EmailMessage email : source.fetchLatest(30)) {
            assertThat(email.id()).isNotBlank();
            assertThat(email.senderName()).isNotBlank();
            assertThat(email.senderAddress()).contains("@");
            assertThat(email.subject()).isNotBlank();
            assertThat(email.body()).startsWith("Hi,").contains(email.senderName());
            assertThat(email.receivedAt()).isBeforeOrEqualTo(NOW);
        }
    }

    @Test
    void sameSeedGivesSameData() {
        var a = new DemoMailSource(new Random(7), clock).fetchLatest(5);
        var b = new DemoMailSource(new Random(7), clock).fetchLatest(5);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void eachPullReturnsFreshData() {
        var source = new DemoMailSource(new Random(7), clock);

        assertThat(source.fetchLatest(5)).isNotEqualTo(source.fetchLatest(5));
    }

    @Test
    void reportsDemoMode() {
        assertThat(new DemoMailSource().mode()).isEqualTo(MailMode.DEMO);
    }

    @Test
    void simulatedNewMailIsZeroToThreeRecentMailsNewestFirst() {
        var sizes = new java.util.HashSet<Integer>();
        // successive pulls on one source, as in the app (neighbouring seeds of java.util.Random start too alike)
        var source = new DemoMailSource(new Random(42), clock);
        for (int pull = 0; pull < 40; pull++) {
            List<EmailMessage> emails = source.fetchNewSimulated();

            sizes.add(emails.size());
            assertThat(emails).allSatisfy(email -> assertThat(email.receivedAt())
                    .isBetween(NOW.minusSeconds(300), NOW));
            assertThat(emails).extracting(EmailMessage::receivedAt)
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        }
        // every count from 0 to 3 occurs, and nothing else
        assertThat(sizes).containsExactlyInAnyOrder(0, 1, 2, 3);
    }
}
