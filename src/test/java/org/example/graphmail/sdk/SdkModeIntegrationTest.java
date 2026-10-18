package org.example.graphmail.sdk;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The whole app in SDK mode against {@link FakeGraphServer}. azure-identity refuses a non-https authority, so a stub
 * {@link TokenCredential} replaces {@code ClientSecretCredential}; everything else is the real Graph SDK.
 */
@SpringBootTest(properties = {
        "graph.mode=sdk",
        "graph.tenant-id=" + FakeGraphServer.TENANT, "graph.client-id=c", "graph.client-secret=s",
        "graph.mailbox=user@test",
        "spring.http.clients.read-timeout=1s"})
@AutoConfigureTestDatabase   // an embedded database, even if config/application.yml or the environment names another
class SdkModeIntegrationTest {

    private static final FakeGraphServer GRAPH = new FakeGraphServer();

    /** Credentials created so far; each one hands out a single token, "sdk-<its number>", like a caching credential. */
    static final AtomicInteger CREDENTIALS = new AtomicInteger();
    static final List<List<String>> REQUESTED_SCOPES = new CopyOnWriteArrayList<>();
    static volatile boolean failTokenRequests;

    @TestConfiguration
    static class StubCredential {
        /** Prototype, so every client rebuild gets a new credential, as with the real {@code ClientSecretCredential}. */
        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        TokenCredential sdkTestCredential() {
            String token = "sdk-" + CREDENTIALS.incrementAndGet();
            return request -> {
                REQUESTED_SCOPES.add(List.copyOf(request.getScopes()));
                return failTokenRequests
                        ? Mono.error(new IllegalStateException("token request refused"))
                        : Mono.just(new AccessToken(token, OffsetDateTime.now().plusHours(1)));
            };
        }
    }

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

    private MailSource sdk;

    @BeforeEach
    void setUp() {
        GRAPH.reset();
        REQUESTED_SCOPES.clear();
        sdk = sources.get(MailMode.SDK);
    }

    @AfterEach
    void allowTokens() {
        failTokenRequests = false;
    }

    private static String newestToken() {
        return "Bearer sdk-" + CREDENTIALS.get();
    }

    private static List<String> authorizations() {
        return GRAPH.messageRequests.stream().map(r -> r.header("Authorization")).toList();
    }

    @Test
    void fetchesMessagesThroughTheSdk() {
        GRAPH.replyToMessages(Reply.ok(FakeGraphServer.TWO_MESSAGES));

        List<EmailMessage> emails = sdk.fetchLatest(2);

        assertThat(emails).containsExactly(
                new EmailMessage("m1", "Ada", "ada@example.com", "Hello", Instant.parse("2026-10-18T10:15:30Z"), "Hi there"),
                new EmailMessage("m2", null, null, "Second", Instant.parse("2026-10-17T08:00:00Z"), ""));
        assertThat(GRAPH.messageRequests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/v1.0/users/user@test/mailFolders/inbox/messages");
            assertThat(request.params()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "$top", "2",
                    "$orderby", "receivedDateTime desc",
                    "$select", "id,subject,from,receivedDateTime,body"));
            assertThat(request.header("Authorization")).isEqualTo(newestToken());
            assertThat(request.header("Prefer")).isEqualTo("outlook.body-content-type=\"text\"");
        });
        assertThat(REQUESTED_SCOPES).containsOnly(List.of("https://graph.microsoft.com/.default"));
        assertThat(GRAPH.tokenRequests).isEmpty();   // tokens come from the credential, not the fake token endpoint
    }

    @Test
    void reusesTheClientAndItsCredential() {
        sdk.fetchLatest(1);
        sdk.fetchLatest(1);

        assertThat(authorizations()).containsExactly(newestToken(), newestToken());
    }

    @Test
    void after401RetriesOnceWithANewCredential() {
        sdk.fetchLatest(1);   // make sure a client exists, so the retry has to replace it
        String before = newestToken();
        GRAPH.reset();
        GRAPH.replyToMessages(Reply.graphError(401));

        assertThat(sdk.fetchLatest(1)).isEmpty();

        assertThat(authorizations()).containsExactly(before, newestToken());
        assertThat(newestToken()).isNotEqualTo(before);
    }

    @Test
    void givesUpAfterASecond401() {
        GRAPH.replyToMessages(Reply.graphError(401), Reply.graphError(401));

        assertThatThrownBy(() -> sdk.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("401");
        assertThat(GRAPH.messageRequests).hasSize(2);
    }

    @Test
    void after403TheNextPullUsesANewCredential() {
        sdk.fetchLatest(1);
        String before = newestToken();
        GRAPH.reset();
        GRAPH.replyToMessages(Reply.graphError(403));

        assertThatThrownBy(() -> sdk.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasMessageContaining("403");
        assertThat(GRAPH.messageRequests).hasSize(1);   // a 403 is not retried

        sdk.fetchLatest(1);

        assertThat(authorizations()).containsExactly(before, newestToken());
        assertThat(newestToken()).isNotEqualTo(before);
    }

    @Test
    void retriesThrottledRequests() {
        // Graph's own middleware in the SDK retries a 429; REST mode does not
        GRAPH.replyToMessages(Reply.throttled(), Reply.ok(FakeGraphServer.TWO_MESSAGES));

        assertThat(sdk.fetchLatest(2)).hasSize(2);
        assertThat(GRAPH.messageRequests).hasSize(2);
    }

    @Test
    void failedTokenRequestFailsThePullWithoutCallingGraph() {
        failTokenRequests = true;

        assertThatThrownBy(() -> sdk.fetchLatest(1))
                .isInstanceOf(MailSourceException.class)
                .hasStackTraceContaining("token request refused");
        assertThat(GRAPH.messageRequests).isEmpty();
    }

    @Test
    void stalledGraphRequestTimesOut() {
        GRAPH.replyToMessages(Reply.stall());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> sdk.fetchLatest(1))
                        .isInstanceOf(MailSourceException.class)
                        // OkHttp closes the socket on a timeout, so the timeout is in the chain, not at its root
                        .hasStackTraceContaining(SocketTimeoutException.class.getName()));
    }

    // --- delta queries ("new mails only") ---

    @Autowired
    NewMailService newMail;

    @Autowired
    StoredEmailRepository storedEmails;

    @Autowired
    SyncStateRepository syncStates;

    private DeltaSource delta() {
        return sources.delta(MailMode.SDK);
    }

    @Test
    void deltaStartAsksForNewMessagesSinceAPointInTime() {
        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage(null, "d1", FakeGraphServer.message("n1", "2026-10-18T10:00:00Z"))));

        DeltaPage page = delta().start(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(page.emails()).extracting(EmailMessage::id).containsExactly("n1");
        assertThat(page.deltaLink()).isEqualTo(GRAPH.deltaUrl("$deltatoken", "d1"));
        assertThat(page.nextLink()).isNull();
        assertThat(GRAPH.deltaRequests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1.0/users/user@test/mailFolders/inbox/messages/delta()");
            assertThat(request.params()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "changeType", "created",
                    "$select", "id,subject,from,receivedDateTime,body",
                    "$filter", "receivedDateTime ge 2026-10-01T00:00:00Z",
                    "$orderby", "receivedDateTime desc"));
            assertThat(request.header("Prefer"))
                    .contains("outlook.body-content-type=\"text\"")
                    .contains("IdType=\"ImmutableId\"")
                    .contains("odata.maxpagesize=50");
            assertThat(request.header("Authorization")).isEqualTo(newestToken());
        });
    }

    @Test
    void deltaFollowsAbsoluteLinksAndSkipsRemovedEntries() {
        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage("p2", null,
                FakeGraphServer.removed("gone"), FakeGraphServer.message("n2", "2026-10-18T10:00:00Z"))));

        DeltaPage page = delta().follow(GRAPH.deltaUrl("$skiptoken", "p1"));

        assertThat(page.emails()).extracting(EmailMessage::id).containsExactly("n2");
        assertThat(page.nextLink()).isEqualTo(GRAPH.deltaUrl("$skiptoken", "p2"));
        assertThat(GRAPH.deltaRequests).singleElement()
                .satisfies(request -> {
                    assertThat(request.params()).containsExactlyEntriesOf(Map.of("$skiptoken", "p1"));
                    assertThat(request.header("Authorization")).isEqualTo(newestToken());
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

        NewMails first = newMail.pullNew(MailMode.SDK);

        assertThat(first.emails()).extracting(EmailMessage::id).containsExactly("s2", "s1");
        assertThat(syncStates.findById("user@test")).get()
                .extracting(SyncState::deltaLink).isEqualTo(GRAPH.deltaUrl("$deltatoken", "d1"));

        GRAPH.replyToDelta(Reply.ok(GRAPH.deltaPage(null, "d2",
                FakeGraphServer.message("s2", "2026-10-18T10:00:00Z"), FakeGraphServer.message("s3", "2026-10-18T11:00:00Z"))));
        NewMails second = newMail.pullNew(MailMode.SDK);

        assertThat(second.emails()).extracting(EmailMessage::id).containsExactly("s3");
        assertThat(GRAPH.deltaRequests.get(2).params()).containsExactlyEntriesOf(Map.of("$deltatoken", "d1"));
        assertThat(storedEmails.count()).isEqualTo(3);
    }
}
