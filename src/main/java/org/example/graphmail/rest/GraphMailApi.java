package org.example.graphmail.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The part of the Graph REST API this app uses, as a Spring HTTP service client. Spring creates the implementation
 * ({@link GraphRestClientConfig}); {@code @ClientRegistrationId} makes Spring Security add an app-only bearer token.
 */
@HttpExchange(accept = "application/json")
@ClientRegistrationId(GraphRestClientConfig.REGISTRATION_ID)
public interface GraphMailApi {

    /** {@code Prefer: outlook.body-content-type="text"} makes Graph return bodies as plain text, not HTML. */
    String PLAIN_TEXT_BODIES = "outlook.body-content-type=\"text\"";

    /**
     * For delta pages: plain-text bodies, immutable ids (which stay the same when a mail moves between folders, so
     * the stored ids keep matching), and at most 50 messages per page.
     */
    String PLAIN_TEXT_BODIES_DELTA_PAGES =
            PLAIN_TEXT_BODIES + ", IdType=\"ImmutableId\", odata.maxpagesize=50";

    @GetExchange("/users/{mailbox}/mailFolders/inbox/messages")
    MessagePage inboxMessages(
            @PathVariable String mailbox,
            @RequestParam("$top") int top,
            @RequestParam("$orderby") String orderBy,
            @RequestParam("$select") String select,
            @RequestHeader("Prefer") String prefer);

    /**
     * Starts a delta sync of the Inbox. {@code changeType=created} limits it to new messages; {@code $filter} only
     * supports {@code receivedDateTime ge|gt}, and with it Graph returns at most 5,000 messages, so
     * {@code $orderby=receivedDateTime desc} (the only ordering delta supports) makes those the newest.
     */
    @GetExchange("/users/{mailbox}/mailFolders/inbox/messages/delta")
    DeltaResponse inboxDelta(
            @PathVariable String mailbox,
            @RequestParam("changeType") String changeType,
            @RequestParam("$select") String select,
            @RequestParam("$filter") String filter,
            @RequestParam("$orderby") String orderBy,
            @RequestHeader("Prefer") String prefer);

    /** Follows a delta sync's nextLink or deltaLink, an absolute Graph URL that already holds the query. */
    @GetExchange
    DeltaResponse followDelta(URI link, @RequestHeader("Prefer") String prefer);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagePage(List<GraphMessage> value) {
    }

    /** Graph's error body, {@code {"error":{"code":...,"message":...}}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphError(ErrorDetail error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorDetail(String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeltaResponse(
            List<GraphMessage> value,
            @JsonProperty("@odata.nextLink") String nextLink,
            @JsonProperty("@odata.deltaLink") String deltaLink) {
    }

    /** {@code removed} is set on delta entries for messages deleted or moved out of the folder. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphMessage(String id, String subject, From from, Instant receivedDateTime, Body body,
                        @JsonProperty("@removed") Map<String, Object> removed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record From(EmailAddress emailAddress) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmailAddress(String name, String address) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(String contentType, String content) {
    }
}
