package org.example.graphmail.sdk;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import com.microsoft.graph.models.Recipient;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import com.microsoft.graph.users.item.mailfolders.item.MailFolderItemRequestBuilder;
import com.microsoft.graph.users.item.mailfolders.item.messages.delta.DeltaGetResponse;
import com.microsoft.kiota.authentication.AzureIdentityAuthenticationProvider;
import okhttp3.OkHttpClient;
import org.example.graphmail.config.GraphProperties;
import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.DeltaSource;
import org.example.graphmail.service.DeltaTokenExpiredException;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSource;
import org.example.graphmail.service.MailSourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.HttpClientSettings;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * SDK mode: reads the Inbox with the Microsoft Graph Java SDK, either the newest messages or a delta sync of new
 * ones. Tokens come from azure-identity
 * ({@code ClientSecretCredential}), which caches them itself. Both the SDK's HTTP client and the credential's get the
 * same {@code spring.http.clients.*} timeouts as REST mode; the SDK keeps Graph's own middleware, which retries
 * throttled (429/503/504) requests.
 * <p>
 * When Graph rejects the token (401 or 403), the client and its credential are rebuilt for the next request, which
 * drops the credential's cached token; after a 401 the request is retried once.
 */
public class SdkMailSource implements MailSource, DeltaSource {

    private static final Logger log = LoggerFactory.getLogger(SdkMailSource.class);
    private static final String[] SELECT = {"id", "subject", "from", "receivedDateTime", "body"};
    private static final String PLAIN_TEXT_BODIES = "outlook.body-content-type=\"text\"";
    /**
     * For delta pages: plain-text bodies, immutable ids (which stay the same when a mail moves between folders, so
     * the stored ids keep matching), and at most 50 messages per page.
     */
    private static final String PLAIN_TEXT_BODIES_DELTA_PAGES =
            PLAIN_TEXT_BODIES + ", IdType=\"ImmutableId\", odata.maxpagesize=50";

    private final GraphProperties props;
    private final Function<HttpClient, TokenCredential> credentials;
    // created once and shared by every rebuilt client, so a rebuild doesn't leave connection pools behind
    private final HttpClient tokenHttpClient;
    private final OkHttpClient graphHttpClient;

    private GraphServiceClient client;

    /**
     * @param credentials creates a new credential, on the given HTTP client, each time the Graph client is rebuilt;
     *                    normally {@link #clientSecretCredential(GraphProperties, HttpClient)}
     */
    public SdkMailSource(GraphProperties props, Function<HttpClient, TokenCredential> credentials,
                         HttpClientSettings httpSettings) {
        this.props = props;
        this.credentials = credentials;
        this.tokenHttpClient = tokenHttpClient(httpSettings);
        this.graphHttpClient = graphHttpClient(httpSettings);
        log.info("SDK mode source ready: mailbox={}", props.mailbox());
    }

    /**
     * The production credential: app-only client credentials against the configured tenant. A new instance has an
     * empty token cache, which is what rebuilding after a rejected token relies on.
     */
    public static TokenCredential clientSecretCredential(GraphProperties props, HttpClient httpClient) {
        return new ClientSecretCredentialBuilder()
                .authorityHost(props.authorityBaseUrl())
                .tenantId(props.tenantId())
                .clientId(props.clientId())
                .clientSecret(props.clientSecret())
                .httpClient(httpClient)
                .build();
    }

    /** azure-core's HTTP client for the credential's token requests, with the configured timeouts. */
    static HttpClient tokenHttpClient(HttpClientSettings httpSettings) {
        var options = new HttpClientOptions();
        if (httpSettings.connectTimeout() != null) {
            options.setConnectTimeout(httpSettings.connectTimeout());
        }
        if (httpSettings.readTimeout() != null) {
            options.setResponseTimeout(httpSettings.readTimeout());
            options.setReadTimeout(httpSettings.readTimeout());
        }
        return HttpClient.createDefault(options);
    }

    /** Graph's OkHttp client with its default middleware (incl. the throttling retry) and the configured timeouts. */
    static OkHttpClient graphHttpClient(HttpClientSettings httpSettings) {
        // the Graph service library's options, so the SdkVersion telemetry header names graph-java and its version
        OkHttpClient.Builder http = GraphClientFactory.create(GraphServiceClient.getGraphClientOptions());
        if (httpSettings.connectTimeout() != null) {
            http.connectTimeout(httpSettings.connectTimeout());
        }
        if (httpSettings.readTimeout() != null) {
            http.readTimeout(httpSettings.readTimeout());
        }
        return http.build();
    }

    @Override
    public MailMode mode() {
        return MailMode.SDK;
    }

    @Override
    public List<EmailMessage> fetchLatest(int limit) {
        log.debug("SDK: fetching latest {} emails from mailbox {}", limit, props.mailbox());
        MessageCollectionResponse page = requestPage(limit, true);
        if (page == null || page.getValue() == null) {
            log.debug("SDK: Graph returned an empty response");
            return List.of();
        }
        List<EmailMessage> result = page.getValue().stream().map(SdkMailSource::toEmail).toList();
        log.debug("SDK: Graph returned {} emails", result.size());
        return result;
    }

    private MessageCollectionResponse requestPage(int limit, boolean retryOnUnauthorized) {
        return call(graph -> inbox(graph).messages().get(request -> {
            request.queryParameters.top = limit;
            request.queryParameters.orderby = new String[]{"receivedDateTime desc"};
            request.queryParameters.select = SELECT;
            request.headers.add("Prefer", PLAIN_TEXT_BODIES);
        }), retryOnUnauthorized);
    }

    @Override
    public DeltaPage start(Instant since) {
        log.debug("SDK: starting a delta sync of mailbox {} for mail received since {}", props.mailbox(), since);
        return toPage(call(graph -> inbox(graph).messages().delta().get(request -> {
            request.queryParameters.changeType = "created";
            request.queryParameters.select = SELECT;
            request.queryParameters.filter = "receivedDateTime ge " + since;
            // with a $filter Graph returns at most 5,000 messages; this (the only ordering delta supports) keeps the newest
            request.queryParameters.orderby = new String[]{"receivedDateTime desc"};
            request.headers.add("Prefer", PLAIN_TEXT_BODIES_DELTA_PAGES);
        }), true));
    }

    @Override
    public DeltaPage follow(String link) {
        log.debug("SDK: following delta link");
        return toPage(call(graph -> inbox(graph).messages().delta().withUrl(link)
                .get(request -> request.headers.add("Prefer", PLAIN_TEXT_BODIES_DELTA_PAGES)), true));
    }

    private MailFolderItemRequestBuilder inbox(GraphServiceClient graph) {
        return graph.users().byUserId(props.mailbox()).mailFolders().byMailFolderId("inbox");
    }

    /**
     * Runs a Graph call. After a 401 or 403 the client and its credential are rebuilt for the next request; a 401 is
     * retried once. A 410, or a 4xx with a code such as {@code syncStateNotFound}, means the delta link expired.
     */
    private <T> T call(Function<GraphServiceClient, T> request, boolean retryOnUnauthorized) {
        GraphServiceClient used = null;
        try {
            used = client();
            return request.apply(used);
        } catch (ApiException e) {
            int status = e.getResponseStatusCode();
            if (status == 401 || status == 403) {
                log.debug("SDK: Graph rejected the access token ({}), rebuilding the client and its credential", status);
                dropClient(used);
                if (status == 401 && retryOnUnauthorized) {
                    log.debug("SDK: retrying once with a new token");
                    return call(request, false);
                }
            }
            String code = e instanceof ODataError odata && odata.getError() != null ? odata.getError().getCode() : null;
            if (DeltaTokenExpiredException.isExpiry(status, code)) {
                log.info("SDK: Graph rejected the delta link ({} {})", status, code);
                throw new DeltaTokenExpiredException(status + (code == null ? "" : " " + code), e);
            }
            throw failed(status + " " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // network failures and timeouts, or the credential failing to get a token
            throw failed(e.getMessage(), e);
        }
    }

    private static DeltaPage toPage(DeltaGetResponse response) {
        if (response == null) {
            throw new MailSourceException("Graph returned an empty delta response");
        }
        List<EmailMessage> emails = Optional.ofNullable(response.getValue()).orElse(List.of()).stream()
                .filter(m -> m.getAdditionalData() == null || !m.getAdditionalData().containsKey("@removed"))
                .map(SdkMailSource::toEmail)
                .toList();
        log.debug("SDK: delta page with {} new emails, {}", emails.size(),
                response.getOdataDeltaLink() != null ? "sync complete" : "more pages");
        return new DeltaPage(emails, response.getOdataNextLink(), response.getOdataDeltaLink());
    }

    private synchronized GraphServiceClient client() {
        if (client == null) {
            log.debug("SDK: creating Graph client for {}", props.graphBaseUrl());
            client = createClient(credentials.apply(tokenHttpClient));
        }
        return client;
    }

    /** Drops the client only if it is still the one that failed, so a client another pull just built survives. */
    private synchronized void dropClient(GraphServiceClient failed) {
        if (client == failed) {
            client = null;
        }
    }

    private GraphServiceClient createClient(TokenCredential credential) {
        // only send the token to the configured Graph host
        String[] allowedHosts = {URI.create(props.graphBaseUrl()).getHost()};
        var authentication = new AzureIdentityAuthenticationProvider(credential, allowedHosts, props.scope());
        var graph = new GraphServiceClient(authentication, graphHttpClient);
        graph.getRequestAdapter().setBaseUrl(props.graphBaseUrl());
        return graph;
    }

    private static MailSourceException failed(String message, RuntimeException e) {
        log.error("SDK: Graph request failed: {}", message);
        return new MailSourceException("Graph request failed: " + message, e);
    }

    private static EmailMessage toEmail(Message m) {
        var address = Optional.ofNullable(m.getFrom()).map(Recipient::getEmailAddress);
        return new EmailMessage(
                m.getId(),
                address.map(com.microsoft.graph.models.EmailAddress::getName).orElse(null),
                address.map(com.microsoft.graph.models.EmailAddress::getAddress).orElse(null),
                m.getSubject(),
                Optional.ofNullable(m.getReceivedDateTime()).map(OffsetDateTime::toInstant).orElse(null),
                Optional.ofNullable(m.getBody()).map(ItemBody::getContent).orElse(""));
    }
}
