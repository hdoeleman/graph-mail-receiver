package org.example.graphmail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole app in demo mode, which needs no credentials. The mode is pinned because inline test properties
 * outrank a local config/application.yml or an exported GRAPH_MODE, which would otherwise change the default.
 */
@SpringBootTest(properties = "graph.mode=demo")
@AutoConfigureMockMvc
@AutoConfigureTestDatabase   // an embedded database, even if config/application.yml or the environment names another
class GraphMailReceiverApplicationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HttpClientSettings httpClientSettings;

    @Test
    void shipsBoundedHttpTimeouts() {
        // the values from application.yml, as Boot sees them; Boot itself has no default timeouts
        assertThat(httpClientSettings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(httpClientSettings.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void demoModeServesGeneratedEmails() throws Exception {
        mvc.perform(get("/api/modes")).andExpect(jsonPath("$.defaultMode").value("demo"));
        mvc.perform(get("/api/emails").param("mode", "demo").param("limit", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].senderAddress").exists());
    }

    @Test
    void servesUiWithModeSwitchPullButtonAndDarkMode() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("name=\"mode\" value=\"demo\""),
                        org.hamcrest.Matchers.containsString("name=\"mode\" value=\"rest\""),
                        org.hamcrest.Matchers.containsString("name=\"mode\" value=\"sdk\""),
                        org.hamcrest.Matchers.containsString("id=\"pull\""),
                        org.hamcrest.Matchers.containsString("data-theme=\"dark\""))));
    }
}
