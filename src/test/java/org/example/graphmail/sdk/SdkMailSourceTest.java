package org.example.graphmail.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.HttpClientSettings;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMailSourceTest {

    @Test
    void graphHttpClientGetsTheConfiguredTimeoutsAndGraphsMiddleware() {
        var settings = HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(30));

        var client = SdkMailSource.graphHttpClient(settings);

        assertThat(client.connectTimeoutMillis()).isEqualTo(5_000);
        assertThat(client.readTimeoutMillis()).isEqualTo(30_000);
        assertThat(client.interceptors()).anySatisfy(interceptor ->
                assertThat(interceptor.getClass().getSimpleName()).isEqualTo("RetryHandler"));
    }
}
