package org.example.graphmail.config;

import org.example.graphmail.service.MailMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPropertiesTest {

    private static GraphProperties props(String tenantId, String clientId, String clientSecret, String mailbox) {
        return new GraphProperties(MailMode.REST, tenantId, clientId, clientSecret, mailbox,
                "https://login.test", "https://graph.test/v1.0", "https://graph.microsoft.com/.default",
                Duration.ofDays(30));
    }

    @Test
    void stripsIdsAndMailbox() {
        var props = props(" t ", "\tc", "s", " user@test \n");

        assertThat(props.tenantId()).isEqualTo("t");
        assertThat(props.clientId()).isEqualTo("c");
        assertThat(props.mailbox()).isEqualTo("user@test");
    }

    @Test
    void nullModeMeansDemoAndNullWindowMeans30Days() {
        var props = new GraphProperties(null, null, null, null, null, null, null, null, null);

        assertThat(props.mode()).isEqualTo(MailMode.DEMO);
        assertThat(props.initialSyncWindow()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void tokenUriIsTheTenantsV2Endpoint() {
        assertThat(props("t", "c", "s", "user@test").tokenUri()).isEqualTo("https://login.test/t/oauth2/v2.0/token");
    }

    @Test
    void completeSettingsHaveNothingMissing() {
        assertThat(props("t", "c", "s", "user@test").missingGraphSettings()).isEmpty();
    }

    @Test
    void reportsEveryMissingSetting() {
        assertThat(props(null, "", " ", "\t").missingGraphSettings()).containsExactly(
                "graph.tenant-id (GRAPH_TENANT_ID)",
                "graph.client-id (GRAPH_CLIENT_ID)",
                "graph.client-secret (GRAPH_CLIENT_SECRET)",
                "graph.mailbox (GRAPH_MAILBOX)");
    }

    @ParameterizedTest
    @CsvSource({
            "tenant, graph.tenant-id (GRAPH_TENANT_ID)",
            "client, graph.client-id (GRAPH_CLIENT_ID)",
            "secret, graph.client-secret (GRAPH_CLIENT_SECRET)",
            "mailbox, graph.mailbox (GRAPH_MAILBOX)"})
    void reportsEachMissingSettingOnItsOwn(String blank, String expected) {
        var props = props(
                blank.equals("tenant") ? " " : "t",
                blank.equals("client") ? " " : "c",
                blank.equals("secret") ? " " : "s",
                blank.equals("mailbox") ? " " : "user@test");

        assertThat(props.missingGraphSettings()).containsExactly(expected);
    }

    @Test
    void shippedApplicationYmlDefaultsToDemoMode() throws IOException {
        // only application.yml, without environment variables, so ${GRAPH_MODE:demo} resolves to its default
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        var binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        assertThat(binder.bind("graph.mode", MailMode.class).get()).isEqualTo(MailMode.DEMO);
    }
}
