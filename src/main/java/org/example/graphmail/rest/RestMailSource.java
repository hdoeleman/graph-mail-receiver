package org.example.graphmail.rest;

import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.rest.GraphMailApi.Body;
import org.example.graphmail.rest.GraphMailApi.DeltaResponse;
import org.example.graphmail.rest.GraphMailApi.EmailAddress;
import org.example.graphmail.rest.GraphMailApi.From;
import org.example.graphmail.rest.GraphMailApi.GraphError;
import org.example.graphmail.rest.GraphMailApi.GraphMessage;
import org.example.graphmail.rest.GraphMailApi.MessagePage;
import org.example.graphmail.service.DeltaSource;
import org.example.graphmail.service.DeltaTokenExpiredException;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSource;
import org.example.graphmail.service.MailSourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * REST mode: reads the Inbox through {@link GraphMailApi}, either the newest messages or a delta sync of new ones.
 * After a 401 the request is retried once; by then {@link GraphRestClientConfig} has dropped the rejected token, so
 * the retry gets a new one.
 */
public class RestMailSource implements MailSource, DeltaSource {

    static final String ORDER_BY = "receivedDateTime desc";
    static final String SELECT = "id,subject,from,receivedDateTime,body";

    private static final Logger log = LoggerFactory.getLogger(RestMailSource.class);

    private final GraphMailApi api;
    private final String mailbox;

    public RestMailSource(GraphMailApi api, String mailbox) {
        this.api = api;
        this.mailbox = mailbox;
        log.info("REST mode source ready: mailbox={}", mailbox);
    }

    @Override
    public MailMode mode() {
        return MailMode.REST;
    }

    @Override
    public List<EmailMessage> fetchLatest(int limit) {
        log.debug("REST: fetching latest {} emails from mailbox {}", limit, mailbox);
        MessagePage page = call(() -> api.inboxMessages(mailbox, limit, ORDER_BY, SELECT, GraphMailApi.PLAIN_TEXT_BODIES));
        if (page == null || page.value() == null) {
            log.debug("REST: Graph returned an empty response");
            return List.of();
        }
        List<EmailMessage> result = page.value().stream().map(RestMailSource::toEmail).toList();
        log.debug("REST: Graph returned {} emails", result.size());
        return result;
    }

    @Override
    public DeltaPage start(Instant since) {
        log.debug("REST: starting a delta sync of mailbox {} for mail received since {}", mailbox, since);
        return toPage(call(() -> api.inboxDelta(mailbox, "created", SELECT, "receivedDateTime ge " + since, ORDER_BY,
                GraphMailApi.PLAIN_TEXT_BODIES_DELTA_PAGES)));
    }

    @Override
    public DeltaPage follow(String link) {
        log.debug("REST: following delta link");
        return toPage(call(() -> api.followDelta(URI.create(link), GraphMailApi.PLAIN_TEXT_BODIES_DELTA_PAGES)));
    }

    /** Runs a Graph call, retrying once after a 401, and turns failures into this app's exceptions. */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.debug("REST: Graph rejected the access token (401), retrying once with a new token");
            try {
                return request.get();
            } catch (RuntimeException retryFailure) {
                throw translate(retryFailure);
            }
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(RuntimeException e) {
        if (e instanceof HttpClientErrorException clientError) {
            int status = clientError.getStatusCode().value();
            String code = errorCode(clientError);
            if (DeltaTokenExpiredException.isExpiry(status, code)) {
                log.info("REST: Graph rejected the delta link ({} {})", status, code);
                return new DeltaTokenExpiredException(status + (code == null ? "" : " " + code), e);
            }
        }
        // a RestClientException from Graph, or Spring Security's ClientAuthorizationException when the token
        // request itself fails
        log.error("REST: Graph request failed: {}", e.getMessage());
        return new MailSourceException("Graph request failed: " + e.getMessage(), e);
    }

    /** The {@code error.code} of a Graph error response, or null when the body isn't one. */
    private static String errorCode(HttpClientErrorException e) {
        try {
            GraphError body = e.getResponseBodyAs(GraphError.class);
            return body == null || body.error() == null ? null : body.error().code();
        } catch (RuntimeException notAGraphError) {
            return null;
        }
    }

    private static DeltaPage toPage(DeltaResponse response) {
        if (response == null) {
            throw new MailSourceException("Graph returned an empty delta response");
        }
        List<EmailMessage> emails = Optional.ofNullable(response.value()).orElse(List.of()).stream()
                .filter(m -> m.removed() == null)   // deletions and moves out of the Inbox
                .map(RestMailSource::toEmail)
                .toList();
        log.debug("REST: delta page with {} new emails, {}", emails.size(),
                response.deltaLink() != null ? "sync complete" : "more pages");
        return new DeltaPage(emails, response.nextLink(), response.deltaLink());
    }

    private static EmailMessage toEmail(GraphMessage m) {
        var address = Optional.ofNullable(m.from()).map(From::emailAddress);
        return new EmailMessage(
                m.id(),
                address.map(EmailAddress::name).orElse(null),
                address.map(EmailAddress::address).orElse(null),
                m.subject(),
                m.receivedDateTime(),
                Optional.ofNullable(m.body()).map(Body::content).orElse(""));
    }
}
