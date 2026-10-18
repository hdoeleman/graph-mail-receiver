package org.example.graphmail.web;

import org.example.graphmail.config.SecurityConfig;
import org.example.graphmail.model.EmailMessage;
import org.example.graphmail.service.MailMode;
import org.example.graphmail.service.MailModeUnavailableException;
import org.example.graphmail.service.MailSource;
import org.example.graphmail.service.MailSourceException;
import org.example.graphmail.service.MailSources;
import org.example.graphmail.service.MailSources.ModeStatus;
import org.example.graphmail.sync.NewMailService;
import org.example.graphmail.sync.NewMailService.NewMails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MailController.class)
@Import(SecurityConfig.class)
class MailControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MailSources mailSources;

    @MockitoBean
    NewMailService newMail;

    private final MailSource demo = mock(MailSource.class);
    private final MailSource rest = mock(MailSource.class);

    @BeforeEach
    void setUp() {
        when(mailSources.defaultMode()).thenReturn(MailMode.DEMO);
        when(mailSources.get(MailMode.DEMO)).thenReturn(demo);
        when(mailSources.get(MailMode.REST)).thenReturn(rest);
    }

    @Test
    void returnsEmailsAsJson() throws Exception {
        when(demo.fetchLatest(10)).thenReturn(List.of(new EmailMessage("m1", "Ada", "ada@example.com",
                "Hello", Instant.parse("2026-10-18T10:15:30Z"), "Hi there")));

        mvc.perform(get("/api/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderName").value("Ada"))
                .andExpect(jsonPath("$[0].senderAddress").value("ada@example.com"))
                .andExpect(jsonPath("$[0].subject").value("Hello"))
                .andExpect(jsonPath("$[0].body").value("Hi there"))
                .andExpect(jsonPath("$[0].receivedAt").value("2026-10-18T10:15:30Z"));
    }

    @Test
    void usesTheModeOfThePull() throws Exception {
        when(rest.fetchLatest(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails").param("mode", "REST")).andExpect(status().isOk());

        verify(rest).fetchLatest(10);
        verify(demo, never()).fetchLatest(anyInt());
    }

    @Test
    void pullWithoutModeUsesTheConfiguredDefault() throws Exception {
        when(mailSources.defaultMode()).thenReturn(MailMode.REST);
        when(rest.fetchLatest(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails")).andExpect(status().isOk());

        verify(rest).fetchLatest(10);
        verify(demo, never()).fetchLatest(anyInt());
    }

    @Test
    void unknownModeIsBadRequest() throws Exception {
        mvc.perform(get("/api/emails").param("mode", "imap"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown mode 'imap', expected one of demo, rest, sdk"));
    }

    @Test
    void unavailableModeIsBadRequest() throws Exception {
        when(mailSources.get(MailMode.SDK)).thenThrow(new MailModeUnavailableException(MailMode.SDK, "no settings"));

        mvc.perform(get("/api/emails").param("mode", "sdk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mode sdk is not available: no settings"));
    }

    @Test
    void clampsLimitToMaximum() throws Exception {
        when(demo.fetchLatest(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails").param("limit", "9999")).andExpect(status().isOk());

        verify(demo).fetchLatest(MailController.MAX_LIMIT);
    }

    @Test
    void clampsLimitToMinimum() throws Exception {
        when(demo.fetchLatest(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails").param("limit", "-3")).andExpect(status().isOk());

        verify(demo).fetchLatest(1);
    }

    @Test
    void sourceFailureBecomesBadGateway() throws Exception {
        when(demo.fetchLatest(anyInt())).thenThrow(new MailSourceException("graph is down"));

        mvc.perform(get("/api/emails"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("graph is down"));
    }

    @Test
    void newMailPullIsAPostWithTheChosenMode() throws Exception {
        when(newMail.pullNew(MailMode.SDK)).thenReturn(new NewMails(List.of(new EmailMessage("m9", "Ada",
                "ada@example.com", "New", Instant.parse("2026-10-18T10:15:30Z"), "Hi")), true, 42, true));

        mvc.perform(post("/api/emails/new").param("mode", "sdk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].id").value("m9"))
                .andExpect(jsonPath("$.stored").value(true))
                .andExpect(jsonPath("$.storedTotal").value(42))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    void newMailPullWithoutModeUsesTheDefault() throws Exception {
        when(mailSources.defaultMode()).thenReturn(MailMode.REST);
        when(newMail.pullNew(MailMode.REST)).thenReturn(new NewMails(List.of(), true, 0, true));

        mvc.perform(post("/api/emails/new")).andExpect(status().isOk());

        verify(newMail).pullNew(MailMode.REST);
    }

    @Test
    void newMailPullFailureBecomesBadGateway() throws Exception {
        when(newMail.pullNew(MailMode.REST)).thenThrow(new MailSourceException("graph is down"));

        mvc.perform(post("/api/emails/new").param("mode", "rest"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("graph is down"));
    }

    @Test
    void storedEmailsClampTheLimit() throws Exception {
        when(newMail.stored(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails/stored").param("limit", "100000")).andExpect(status().isOk());

        verify(newMail).stored(MailController.MAX_STORED_LIMIT);
    }

    @Test
    void storedEmailsClampTheLimitToAtLeastOne() throws Exception {
        when(newMail.stored(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/emails/stored").param("limit", "-3")).andExpect(status().isOk());

        verify(newMail).stored(1);
    }

    @Test
    void resetSyncReturnsNoContent() throws Exception {
        mvc.perform(post("/api/sync/reset")).andExpect(status().isNoContent());

        verify(newMail).resetSync();
    }

    @Test
    void listsModesWithDefault() throws Exception {
        when(mailSources.defaultMode()).thenReturn(MailMode.SDK);
        when(mailSources.statuses()).thenReturn(List.of(
                new ModeStatus("demo", true, null), new ModeStatus("rest", false, "no settings")));

        mvc.perform(get("/api/modes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMode").value("sdk"))
                .andExpect(jsonPath("$.modes[0].mode").value("demo"))
                .andExpect(jsonPath("$.modes[0].available").value(true))
                .andExpect(jsonPath("$.modes[1].available").value(false))
                .andExpect(jsonPath("$.modes[1].reason").value("no settings"));
    }
}
