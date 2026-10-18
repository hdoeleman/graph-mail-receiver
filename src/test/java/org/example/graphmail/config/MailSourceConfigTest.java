package org.example.graphmail.config;

import org.example.graphmail.rest.GraphMailApi;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailModeUnavailableException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.service.MailSources.ModeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Which modes are available for which settings, the default mode, and the startup check. */
class MailSourceConfigTest {

    private static final String[] CREDENTIALS = {
            "graph.tenant-id=t", "graph.client-id=c", "graph.client-secret=s", "graph.mailbox=user@test"};
    private static final String MISSING = "these settings are empty: "
            + "graph.tenant-id (GRAPH_TENANT_ID), graph.client-id (GRAPH_CLIENT_ID), "
            + "graph.client-secret (GRAPH_CLIENT_SECRET), graph.mailbox (GRAPH_MAILBOX)";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraphProperties.class)
    static class PropertiesConfig {
    }

    // Runs only MailSourceConfig, without config files, environment variables or system properties, so an exported
    // GRAPH_MODE or GRAPH_TENANT_ID can't leak into a case.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> {
                var sources = ctx.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(PropertiesConfig.class, MailSourceConfig.class)
            .withBean(GraphMailApi.class, () -> mock(GraphMailApi.class))
            .withBean(HttpClientSettings.class, HttpClientSettings::defaults);

    @Test
    void withoutCredentialsOnlyDemoIsAvailable() {
        runner.run(ctx -> {
            var sources = ctx.getBean(MailSources.class);
            assertThat(sources.defaultMode()).isEqualTo(MailMode.DEMO);
            assertThat(sources.statuses()).containsExactly(
                    new ModeStatus("demo", true, null),
                    new ModeStatus("rest", false, MISSING),
                    new ModeStatus("sdk", false, MISSING));
            assertThat(sources.get(MailMode.DEMO).mode()).isEqualTo(MailMode.DEMO);
            assertThatThrownBy(() -> sources.get(MailMode.SDK))
                    .isInstanceOf(MailModeUnavailableException.class)
                    .hasMessage("Mode sdk is not available: " + MISSING);
        });
    }

    @Test
    void withCredentialsAllModesAreAvailable() {
        runner.withPropertyValues(CREDENTIALS).run(ctx -> {
            var sources = ctx.getBean(MailSources.class);
            assertThat(sources.statuses()).allMatch(ModeStatus::available);
            for (MailMode mode : MailMode.values()) {
                assertThat(sources.get(mode).mode()).isEqualTo(mode);
            }
        });
    }

    @ParameterizedTest
    @CsvSource({"demo, DEMO", "REST, REST", " Sdk , SDK"})
    void modeSettingPicksTheDefault(String value, MailMode expected) {
        runner.withPropertyValues("graph.mode=" + value).withPropertyValues(CREDENTIALS)
                .run(ctx -> assertThat(ctx.getBean(MailSources.class).defaultMode()).isEqualTo(expected));
    }

    @Test
    void emptyModeKeepsTheDefault() {
        // e.g. GRAPH_MODE set but empty, which makes ${GRAPH_MODE:demo} resolve to ""
        runner.withPropertyValues("graph.mode=")
                .run(ctx -> assertThat(ctx.getBean(MailSources.class).defaultMode()).isEqualTo(MailMode.DEMO));
    }

    @Test
    void unrecognizedModeFailsTheBind() {
        runner.withPropertyValues("graph.mode=imap").withPropertyValues(CREDENTIALS)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining("Failed to bind properties under 'graph.mode'"));
    }

    @ParameterizedTest
    @CsvSource({"rest", "sdk"})
    void startingInAGraphModeWithoutCredentialsFails(String mode) {
        runner.withPropertyValues("graph.mode=" + mode)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("graph.mode is " + mode + ", but " + MISSING + ". Set them, or start in demo mode."));
    }
}
