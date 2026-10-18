package org.example.graphmail.rest;

import org.example.graphmail.FakeGraphServer;
import org.example.graphmail.FakeGraphServer.Reply;
import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.DeltaSource;
import org.example.graphmail.service.DeltaSource.DeltaPage;
import org.example.graphmail.service.DeltaTokenExpiredException;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSource;
import org.example.graphmail.service.MailSourceException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.store.StoredEmailRepository;
import org.example.graphmail.store.SyncState;
import org.example.graphmail.store.SyncStateRepository;
import org.example.graphmail.sync.NewMailService;
import org.example.graphmail.sync.NewMailService.NewMails;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The whole app in REST mode against {@link FakeGraphServer}: the delta queries and a new-mail pull into the database,
 * Spring Security's client-credentials token, the
 * {@code @HttpExchange} Graph request, token caching and dropping, and the timeouts.
 */
@SpringBootTest(properties = {
        "graph.mode=rest",
        "graph.tenant-id=" + FakeGraphServer.TENANT, "graph.client-id=c", "graph.client-secret=s",
        "graph.mailbox=user@test",
        "spring.http.clients.read-timeout=1s"})
@AutoConfigureTestDatabase   // an embedded database, even if config/application.yml or the environment names another
class RestModeIntegrationTest {

    private static final FakeGraphServer GRAPH = new FakeGraphServer();

    @DynamicPropertySource
    static void pointAtFakeGraph(DynamicPropertyRegistry registry) {
        registry.add("graph.authority-base-url", GRAPH::authorityBaseUrl);
        registry.add("graph.graph-base-url", GRAPH::graphBaseUrl);
    }

    @AfterAll
    static void stopFakeGraph() {
        GRAPH.close();
    }

    @Autowired
    MailSources sources;

    @Autowired
    OAuth2AuthorizedClientService authorizedClients;

    private MailSource rest;
    private String firstToken;

    @BeforeEach
    void setUp() {
        GRAPH.reset();
        authorizedClients.removeAuthorizedClient(GraphRestClientConfig.REGISTRATION_ID, "graph-mail-receiver");
        rest = sources.get(MailMode.REST);
        firstToken = "tok-" + GRAPH.nextTokenNumber();
    }

    @Test
    void fetchesMessagesWithAnAppOnlyToken() {
        GRAPH.replyToMessages(Reply.ok(FakeGraphServer.TWO_MESSAGES));

        List<EmailMessage> emails = rest.fetchLatest(2);

        assertThat(emails).containsExactly(
                new EmailMessage("m1", "Ada", "ada@example.com", "Hello", Instant.parse("2026-10-18T10:15:30Z"), "Hi there"),
                new EmailMessage("m2", null, null, "Second", Instant.parse("2026-10-17T08:00:00Z"), ""));

        assertThat(GRAPH.tokenRequests).singleElement().satisfies(token -> {
            assertThat(token.method()).isEqualTo("POST");
            assertThat(token.params()).containsAllEntriesOf(Map.of(
                    "grant_type", "client_credentials",
                    "client_id", "c",
                    "client_secret", "s",
                    "scope", "https://graph.microsoft.com/.default"));
        });
        assertThat(GRAPH.messageRequests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/v1.0/users/user@test/mailFolders/inbox/messages");
            assertThat(request.params()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "$top", "2",
                    "$orderby", "receivedDateTime desc",
                    "$select", "id,subject,from,receivedDateTime,body"));
            assertThat(request.header("Authorization")).isEqualTo("Bearer " + firstToken);
            assertThat(request.header("Prefer")).isEqualTo("outlook.body-content-type=\"text\"");
        });
    }

    @Test
    void reusesTheCachedToken() {
        rest.fetchLatest(1);
        rest.fetchLatest(1);

        assertThat(GRAPH.tokenRequests).hasSize(1);
        assertThat(GRAPH.messageRequests).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer " + firstToken, "Bearer " + firstToken);
    }

    @Test
    void after401RetriesOnceWithANewToken() {
        GRAPH.replyToMessages(Reply.graphError(401));

        assertThat(rest.fetchLatest(1)).isEmpty();

        assertThat(GRAPH.tokenRequests).hasSize(2);
        assertThat(GRAPH.messageRequests).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer " + firstToken, "Bearer tok-" + (GRAPH.nextTokenNumber() - 1));
    }

    @Test
    void givesUpAfterASecond401() {
        GRAPH.replyToMessages(Reply.graphError(401), Reply.graphError(401));

        assertThatThrownBy(() -> rest.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("401");
        assertThat(GRAPH.messageRequests).hasSize(2);
    }

    @Test
    void after403TheNextPullUsesANewToken() {
        GRAPH.replyToMessages(Reply.graphError(403));

        assertThatThrownBy(() -> rest.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("403");
        assertThat(GRAPH.messageRequests).hasSize(1);   // a 403 is not retried

        rest.fetchLatest(1);

        assertThat(GRAPH.tokenRequests).hasSize(2);
        assertThat(GRAPH.messageRequests.get(1).header("Authorization")).isNotEqualTo("Bearer " + firstToken);
    }

    @Test
    void tokenEndpointErrorIsParsedAndFailsThePullWithoutCallingGraph() {
        GRAPH.replyToToken(Reply.tokenError(400, "unauthorized_client"));

        assertThatThrownBy(() -> rest.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                // "[code]" is how OAuth2Error renders, so this only matches when OAuth2ErrorResponseErrorHandler
                // parsed the error response, which it does for 400s
                .hasMessageContaining("[unauthorized_client]");
        assertThat(GRAPH.messageRequests).isEmpty();
    }

    @Test
    void tokenEndpoint401ShowsTheErrorBody() {
        // Spring Security parses only 400 error bodies; for a 401 (e.g. a wrong secret) the body is in the message
        GRAPH.replyToToken(Reply.tokenError(401, "invalid_client"));

        assertThatThrownBy(() -> rest.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid_client");
        assertThat(GRAPH.messageRequests).isEmpty();
    }

    @Test
    void stalledTokenRequestTimesOut() {
        GRAPH.replyToToken(Reply.stall());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> rest.fetchLatest(1))
                        .isInstanceOf(MailSourceException.class)
                        .hasRootCauseInstanceOf(HttpTimeoutException.class));
    }

    @Test
    void stalledGraphRequestTimesOut() {
        GRAPH.replyToMessages(Reply.stall());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> rest.fetchLatest(1))
                        .isInstanceOf(MailSourceException.class)
                        .hasRootCauseInstanceOf(HttpTimeoutException.class));
    }

    // --- delta queries ("new mails only") ---

    @Autowired
    NewMailService newMail;

    @Autowired
    StoredEmailRepository storedEmails;

    @Autowired
    SyncStateRepository syncStates;

    private DeltaSource delta() {
        return sources.delta(MailMode.REST);
    }

    @Test
    void deltaStartAsksForNewMessagesSinceAPointInTime() {
        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage(null, "d1", FakeGraphServer.message("n1", "2026-10-18T10:00:00Z"))));

        DeltaPage page = delta().start(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(page.emails()).extracting(EmailMessage::id).containsExactly("n1");
        assertThat(page.deltaLink()).isEqualTo(GRAPH.deltaUrl("$deltatoken", "d1"));
        assertThat(page.nextLink()).isNull();
        assertThat(GRAPH.deltaRequests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1.0/users/user@test/mailFolders/inbox/messages/delta");
            assertThat(request.params()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "changeType", "created",
                    "$select", "id,subject,from,receivedDateTime,body",
                    "$filter", "receivedDateTime ge 2026-10-01T00:00:00Z",
                    "$orderby", "receivedDateTime desc"));
            assertThat(request.header("Prefer"))
                    .contains("outlook.body-content-type=\"text\"")
                    .contains("IdType=\"ImmutableId\"")
                    .contains("odata.maxpagesize=50");
            assertThat(request.header("Authorization")).isEqualTo("Bearer " + firstToken);
        });
    }

    @Test
    void deltaFollowsAbsoluteLinksAndSkipsRemovedEntries() {
        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage("p2", null,
                FakeGraphServer.removed("gone"), FakeGraphServer.message("n2", "2026-10-18T10:00:00Z"))));

        DeltaPage page = delta().follow(GRAPH.deltaUrl("$skiptoken", "p1"));

        assertThat(page.emails()).extracting(EmailMessage::id).containsExactly("n2");
        assertThat(page.nextLink()).isEqualTo(GRAPH.deltaUrl("$skiptoken", "p2"));
        assertThat(GRAPH.deltaRequests).singleElement().satisfies(request -> {
            assertThat(request.params()).containsExactlyEntriesOf(Map.of("$skiptoken", "p1"));
            assertThat(request.header("Authorization")).isEqualTo("Bearer " + firstToken);
            assertThat(request.header("Prefer")).isEqualTo(
                    "outlook.body-content-type=\"text\", IdType=\"ImmutableId\", odata.maxpagesize=50");
        });
    }

    @Test
    void a410MeansTheDeltaLinkExpired() {
        GRAPH.replyToDelta(FakeGraphServer.gone(GRAPH.deltaUrl("$skiptoken", "restart")));

        assertThatThrownBy(() -> delta().follow(GRAPH.deltaUrl("$deltatoken", "old")))
                .isInstanceOf(DeltaTokenExpiredException.class)
                .hasMessageContaining("410");
    }

    @Test
    void a400SyncStateNotFoundAlsoMeansTheDeltaLinkExpired() {
        GRAPH.replyToDelta(FakeGraphServer.graphErrorWithCode(400, "syncStateNotFound"));

        assertThatThrownBy(() -> delta().follow(GRAPH.deltaUrl("$deltatoken", "old")))
                .isInstanceOf(DeltaTokenExpiredException.class)
                .hasMessageContaining("syncStateNotFound");
    }

    @Test
    void anOther400IsAnOrdinaryFailure() {
        GRAPH.replyToDelta(FakeGraphServer.graphErrorWithCode(400, "BadRequest"));

        assertThatThrownBy(() -> delta().follow(GRAPH.deltaUrl("$deltatoken", "old")))
                .isInstanceOf(MailSourceException.class)
                .isNotInstanceOf(DeltaTokenExpiredException.class);
    }

    @Test
    void newMailPullStoresMailAndTheDeltaLinkAndTheNextPullContinuesFromIt() {
        storedEmails.deleteAll();
        syncStates.deleteAll();
        GRAPH.replyToDelta(
                Reply.ok(GRAPH.deltaPage("p2", null, FakeGraphServer.message("s1", "2026-10-18T09:00:00Z"))),
                Reply.ok(GRAPH.deltaPage(null, "d1", FakeGraphServer.message("s2", "2026-10-18T10:00:00Z"))));

        NewMails first = newMail.pullNew(MailMode.REST);

        assertThat(first.emails()).extracting(EmailMessage::id).containsExactly("s2", "s1");
        assertThat(syncStates.findById("user@test")).get()
                .extracting(SyncState::deltaLink).isEqualTo(GRAPH.deltaUrl("$deltatoken", "d1"));

        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage(null, "d2",
                FakeGraphServer.message("s2", "2026-10-18T10:00:00Z"), FakeGraphServer.message("s3", "2026-10-18T11:00:00Z"))));
        NewMails second = newMail.pullNew(MailMode.REST);

        assertThat(second.emails()).extracting(EmailMessage::id).containsExactly("s3");
        assertThat(GRAPH.deltaRequests.get(2).params()).containsExactlyEntriesOf(Map.of("$deltatoken", "d1"));
        assertThat(storedEmails.count()).isEqualTo(3);
    }
}
