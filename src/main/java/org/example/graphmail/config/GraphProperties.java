package org.example.graphmail.config;

import org.example.graphmail.service.MailMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings for talking to Microsoft Graph. Credentials are only needed for the rest and sdk modes.
 * Ids and the mailbox are stripped of surrounding whitespace, which is easy to pick up when copying them.
 *
 * @param mode             the mode the UI starts with (demo, rest or sdk); an empty value (e.g. GRAPH_MODE set but
 *                         empty) counts as unset and keeps the default, demo
 * @param tenantId         Entra ID (Azure AD) tenant
 * @param clientId         app registration (client) id
 * @param clientSecret     app registration secret; never logged
 * @param mailbox          user principal name or object id of the mailbox to read
 * @param authorityBaseUrl base URL of the token endpoint host
 * @param graphBaseUrl     base URL of the Graph API
 * @param scope            OAuth scope requested with the client credentials flow
 * @param initialSyncWindow how far back the first "new mails only" pull looks when nothing is stored yet
 */
@ConfigurationProperties("graph")
public record GraphProperties(
        @DefaultValue("demo") MailMode mode,
        String tenantId,
        String clientId,
        String clientSecret,
        String mailbox,
        @DefaultValue("https://login.microsoftonline.com") String authorityBaseUrl,
        @DefaultValue("https://graph.microsoft.com/v1.0") String graphBaseUrl,
        @DefaultValue("https://graph.microsoft.com/.default") String scope,
        @DefaultValue("30d") Duration initialSyncWindow) {

    public GraphProperties {
        mode = mode == null ? MailMode.DEMO : mode;
        initialSyncWindow = initialSyncWindow == null ? Duration.ofDays(30) : initialSyncWindow;
        tenantId = strip(tenantId);
        clientId = strip(clientId);
        mailbox = strip(mailbox);
    }

    /** The settings the Graph modes need that are empty, as "property (ENV_VAR)" names. */
    public List<String> missingGraphSettings() {
        var missing = new ArrayList<String>();
        if (!StringUtils.hasText(tenantId)) missing.add("graph.tenant-id (GRAPH_TENANT_ID)");
        if (!StringUtils.hasText(clientId)) missing.add("graph.client-id (GRAPH_CLIENT_ID)");
        if (!StringUtils.hasText(clientSecret)) missing.add("graph.client-secret (GRAPH_CLIENT_SECRET)");
        if (!StringUtils.hasText(mailbox)) missing.add("graph.mailbox (GRAPH_MAILBOX)");
        return missing;
    }

    /** The tenant's OAuth2 v2.0 token endpoint. */
    public String tokenUri() {
        return authorityBaseUrl + "/" + tenantId + "/oauth2/v2.0/token";
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
