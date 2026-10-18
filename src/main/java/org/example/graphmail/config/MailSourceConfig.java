package org.example.graphmail.config;

import com.azure.core.credential.TokenCredential;
import org.example.graphmail.rest.GraphMailApi;
import org.example.graphmail.rest.RestMailSource;
import org.example.graphmail.sdk.SdkMailSource;
import org.example.graphmail.service.DemoMailSource;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailSource;
import org.example.graphmail.service.MailSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Creates every mail source this app can use. Demo mode always works. The rest and sdk modes need the four Graph
 * settings; without them they are listed as unavailable, with the missing settings as the reason. Starting with
 * {@code graph.mode} set to rest or sdk while settings are missing fails at startup instead.
 */
@Configuration(proxyBeanMethods = false)
public class MailSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MailSourceConfig.class);

    /** "Now" for the new-mail sync (its first-sync window and stored timestamps); tests can replace it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * @param sdkCredential a {@link TokenCredential} bean replaces the production credential in SDK mode; only tests
     *                      define one
     */
    @Bean
    public MailSources mailSources(GraphProperties props, ObjectProvider<GraphMailApi> graphMailApi,
                                   HttpClientSettings httpClientSettings,
                                   ObjectProvider<TokenCredential> sdkCredential) {
        List<MailSource> sources = new ArrayList<>();
        sources.add(new DemoMailSource());
        var unavailable = new EnumMap<MailMode, String>(MailMode.class);

        List<String> missing = props.missingGraphSettings();
        if (missing.isEmpty()) {
            sources.add(new RestMailSource(graphMailApi.getObject(), props.mailbox()));
            sources.add(new SdkMailSource(props,
                    http -> sdkCredential.getIfAvailable(() -> SdkMailSource.clientSecretCredential(props, http)),
                    httpClientSettings));
        } else {
            String reason = "these settings are empty: " + String.join(", ", missing);
            unavailable.put(MailMode.REST, reason);
            unavailable.put(MailMode.SDK, reason);
            if (props.mode() != MailMode.DEMO) {
                throw new IllegalStateException("graph.mode is " + props.mode().id() + ", but " + reason
                        + ". Set them, or start in demo mode.");
            }
            log.info("Graph settings incomplete, only demo mode is available ({})", reason);
        }
        return new MailSources(props.mode(), sources, unavailable);
    }
}
