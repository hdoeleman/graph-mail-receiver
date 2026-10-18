package org.example.graphmail;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A local stand-in for Entra ID's token endpoint (tenant {@value #TENANT}) and the Graph API ({@code /v1.0}). It
 * records every request, and answers with queued replies, falling back to a fresh token ({@code tok-1},
 * {@code tok-2}, ...), an empty message page, or for delta requests an empty delta page ending in a deltaLink.
 */
public final class FakeGraphServer implements AutoCloseable {

    public static final String TENANT = "t";

    public static final String TWO_MESSAGES = """
            {"@odata.context":"x","value":[
              {"id":"m1","subject":"Hello","receivedDateTime":"2026-10-18T10:15:30Z",
               "from":{"emailAddress":{"name":"Ada","address":"ada@example.com"}},
               "body":{"contentType":"text","content":"Hi there"},"unknownField":1},
              {"id":"m2","subject":"Second","receivedDateTime":"2026-10-17T08:00:00Z"}
            ]}""";

    /** How to answer one request: status, JSON body, extra response headers, and how long to wait first. */
    public record Reply(int status, String body, Map<String, String> headers, Duration delay) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of(), Duration.ZERO);
        }

        /** A Graph-style error; the token endpoint gets an OAuth2-style one from {@link #tokenError}. */
        public static Reply graphError(int status) {
            return new Reply(status, "{\"error\":{\"code\":\"Denied\",\"message\":\"status " + status + "\"}}",
                    Map.of(), Duration.ZERO);
        }

        public static Reply tokenError(int status, String error) {
            return new Reply(status, "{\"error\":\"" + error + "\",\"error_description\":\"" + error + "\"}",
                    Map.of(), Duration.ZERO);
        }

        /** Throttled, with a Retry-After of 0 seconds so a retry happens right away. */
        public static Reply throttled() {
            return new Reply(429, "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"slow down\"}}",
                    Map.of("Retry-After", "0"), Duration.ZERO);
        }

        /** Never answers within any test timeout, like a stalled upstream. */
        public static Reply stall() {
            return new Reply(200, "{}", Map.of(), Duration.ofSeconds(30));
        }
    }

    /** A request as received: decoded path, decoded query or form parameters, and headers. */
    public record Request(String method, String path, Map<String, String> params, Headers headers) {

        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    public final List<Request> tokenRequests = new CopyOnWriteArrayList<>();
    public final List<Request> messageRequests = new CopyOnWriteArrayList<>();
    public final List<Request> deltaRequests = new CopyOnWriteArrayList<>();

    private final Queue<Reply> tokenReplies = new ConcurrentLinkedQueue<>();
    private final Queue<Reply> messageReplies = new ConcurrentLinkedQueue<>();
    private final Queue<Reply> deltaReplies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;

    public FakeGraphServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/" + TENANT + "/oauth2/v2.0/token", exchange -> handle(exchange, tokenRequests,
                tokenReplies, () -> Reply.ok("{\"access_token\":\"tok-" + tokensIssued.incrementAndGet()
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        server.createContext("/v1.0/", exchange -> {
            // the SDK calls the OData function form, "delta()", which Graph accepts as well as "delta"
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/delta") || path.endsWith("/delta()")) {
                handle(exchange, deltaRequests, deltaReplies, () -> Reply.ok(deltaPage(null, "final")));
            } else {
                handle(exchange, messageRequests, messageReplies, () -> Reply.ok("{\"value\":[]}"));
            }
        });
        server.start();
    }

    /** A delta link on this server, as Graph builds them: the Inbox delta URL with a skip or delta token. */
    public String deltaUrl(String tokenParameter, String token) {
        return graphBaseUrl() + "/users/user@test/mailFolders/inbox/messages/delta?" + tokenParameter + "=" + token;
    }

    /**
     * A delta page holding the given message JSON objects, ending with a nextLink ({@code $skiptoken=nextToken})
     * when {@code nextToken} is set, otherwise with a deltaLink ({@code $deltatoken=deltaToken}).
     */
    public String deltaPage(String nextToken, String deltaToken, String... messages) {
        String link = nextToken != null
                ? "\"@odata.nextLink\":\"" + deltaUrl("$skiptoken", nextToken) + "\""
                : "\"@odata.deltaLink\":\"" + deltaUrl("$deltatoken", deltaToken) + "\"";
        return "{\"@odata.context\":\"x\",\"value\":[" + String.join(",", messages) + "]," + link + "}";
    }

    /** A message as a delta page lists it. */
    public static String message(String id, String receivedAt) {
        return "{\"id\":\"" + id + "\",\"subject\":\"subject " + id + "\",\"receivedDateTime\":\"" + receivedAt + "\","
                + "\"from\":{\"emailAddress\":{\"name\":\"Ada\",\"address\":\"ada@example.com\"}},"
                + "\"body\":{\"contentType\":\"text\",\"content\":\"body " + id + "\"}}";
    }

    /** A delta entry for a message deleted or moved out of the folder. */
    public static String removed(String id) {
        return "{\"id\":\"" + id + "\",\"@removed\":{\"reason\":\"deleted\"}}";
    }

    /** A Graph-style error with the given error code, e.g. 400 {@code syncStateNotFound} for an expired delta token. */
    public static Reply graphErrorWithCode(int status, String code) {
        return new Reply(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}}",
                Map.of(), Duration.ZERO);
    }

    /** 410 Gone for an expired delta token, with a restart link in Location when given (the app ignores it). */
    public static Reply gone(String location) {
        return new Reply(410, "{\"error\":{\"code\":\"SyncStateNotFound\",\"message\":\"sync state not found\"}}",
                location == null ? Map.of() : Map.of("Location", location), Duration.ZERO);
    }

    public String authorityBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String graphBaseUrl() {
        return authorityBaseUrl() + "/v1.0";
    }

    public void replyToToken(Reply... replies) {
        tokenReplies.addAll(Arrays.asList(replies));
    }

    public void replyToMessages(Reply... replies) {
        messageReplies.addAll(Arrays.asList(replies));
    }

    /** Replies for {@code .../messages/delta} requests, the first call and every followed link alike. */
    public void replyToDelta(Reply... replies) {
        deltaReplies.addAll(Arrays.asList(replies));
    }

    /** Forgets recorded requests and queued replies; issued token numbers keep counting. */
    public void reset() {
        tokenRequests.clear();
        messageRequests.clear();
        deltaRequests.clear();
        tokenReplies.clear();
        messageReplies.clear();
        deltaReplies.clear();
    }

    /** The number the next default token will carry, e.g. 3 for {@code tok-3}. */
    public int nextTokenNumber() {
        return tokensIssued.get() + 1;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(HttpExchange exchange, List<Request> log, Queue<Reply> replies,
                               Supplier<Reply> fallback) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        URI uri = exchange.getRequestURI();
        var params = new LinkedHashMap<String, String>();
        params.putAll(parse(uri.getRawQuery()));
        params.putAll(parse(body));
        log.add(new Request(exchange.getRequestMethod(), uri.getPath(), params, exchange.getRequestHeaders()));

        Reply reply = replies.poll();
        if (reply == null) {
            reply = fallback.get();
        }
        try {
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client gave up (e.g. it timed out), nothing to answer
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parse(String encoded) {
        var params = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isBlank() || !encoded.contains("=")) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }
}
