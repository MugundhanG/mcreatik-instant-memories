package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.mail.Mailer;
import com.mcreatik.gallery.support.IntegrationTest;

/** McreatiK's per-event gallery switches, and "Forgot password" by email for studios and couples. */
class GalleryAccessAndPasswordResetTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private static final Pattern TEMPORARY = Pattern.compile("Temporary password: (\\S+)");

    @MockitoBean
    Mailer mailer;

    /** A fresh client IP for each public call, so the strict login limit never interferes. */
    private static MockHttpServletRequestBuilder fromNewIp(MockHttpServletRequestBuilder r) {
        int n = IPS.incrementAndGet();
        return r.with(req -> {
            req.setRemoteAddr("10.66." + (n / 250) + "." + (n % 250 + 1));
            return req;
        });
    }

    private ResultActions json(MockHttpServletRequestBuilder r, String token, String body) throws Exception {
        if (token != null) {
            r = r.header("Authorization", bearer(token));
        }
        return mvc.perform(fromNewIp(r).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String studio(String email) throws Exception {
        json(post("/api/admin/studios"), adminToken(),
                "{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}")
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions studioLogin(String email, String password) throws Exception {
        return json(post("/api/auth/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions clientLogin(String email, String password) throws Exception {
        return json(post("/api/client/auth/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** The temporary password in the email sent to this address. */
    private String emailedPassword(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(3000)).send(eq(to), eq("Your McreatiK temporary password"), body.capture(), org.mockito.ArgumentMatchers.contains("mcreatik.com"));
        Matcher m = TEMPORARY.matcher(body.getValue());
        assertThat(m.find()).isTrue();
        assertThat(body.getValue()).contains("Instant Memories login", "Instagram: @mcreatik.studios", "wa.me/919952758545", "connect@mcreatik.com");
        return m.group(1);
    }

    @Test
    void onlyMcreatikTurnsGalleriesOffAndGuestsAndCouplesSeeAFriendlyMessage() throws Exception {
        String studioEmail = studio("switch-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test");
        String studio = JsonPath.read(studioLogin(studioEmail, "temporary-123").andReturn().getResponse().getContentAsString(), "$.token");
        String event = json(post("/api/admin/events"), studio, "{\"name\":\"Arun Priya Wedding\",\"eventDate\":\"2026-12-01\",\"status\":\"LIVE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String eventId = eventId(event);
        String slug = slug(event);
        String clientEmail = "couple-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        json(post("/api/admin/events/" + eventId + "/clients"), studio,
                "{\"name\":\"Arun & Priya\",\"email\":\"" + clientEmail + "\",\"password\":\"temporary-123\"}").andExpect(status().isCreated());
        String client = JsonPath.read(clientLogin(clientEmail, "temporary-123").andReturn().getResponse().getContentAsString(), "$.token");

        // A studio cannot use the switches, even on its own event.
        json(patch("/api/admin/events/" + eventId + "/access"), studio, "{\"liveGallery\":false}").andExpect(status().isForbidden());

        // McreatiK turns both off: guests and the couple get a clear message naming the studio.
        json(patch("/api/admin/events/" + eventId + "/access"), adminToken(), "{\"liveGallery\":false,\"clientGallery\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveGalleryEnabled").value(false))
                .andExpect(jsonPath("$.clientGalleryEnabled").value(false));
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("This gallery is currently unavailable. Please contact Lens & Light."));
        mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(status().isForbidden());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(client))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_UNAVAILABLE"));
        clientLogin(clientEmail, "temporary-123").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_UNAVAILABLE"));

        // The studio still works on the event and sees why the galleries are closed.
        mvc.perform(get("/api/admin/events/" + eventId).header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liveGalleryEnabled").value(false));

        // Turned back on, everything opens again.
        json(patch("/api/admin/events/" + eventId + "/access"), adminToken(), "{\"liveGallery\":true,\"clientGallery\":true}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isOk());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(client))).andExpect(status().isOk());
    }

    @Test
    void aStudioGetsATemporaryPasswordByEmailWithoutLosingItsCurrentOne() throws Exception {
        String email = studio("forgot-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test");
        String oldSession = JsonPath.read(studioLogin(email, "temporary-123").andReturn().getResponse().getContentAsString(), "$.token");

        // Unknown emails get the same answer, and nothing is sent.
        json(post("/api/auth/forgot-password"), null, "{\"email\":\"nobody-here@studio.test\"}").andExpect(status().isNoContent());
        verify(mailer, after(300).never()).send(eq("nobody-here@studio.test"), anyString(), anyString());

        json(post("/api/auth/forgot-password"), null, "{\"email\":\"" + email.toUpperCase() + "\"}").andExpect(status().isNoContent());
        String temporary = emailedPassword(email);
        // A second request straight away sends nothing more.
        clearInvocations(mailer);
        json(post("/api/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        verify(mailer, after(300).never()).send(eq(email), anyString(), anyString());

        // Someone else asking does not lock the studio out: the current password still works.
        studioLogin(email, "temporary-123").andExpect(status().isOk());
        // Logging in with the old password cancelled the pending reset, so ask again and use it this time.
        jdbc.update("UPDATE admin_users SET reset_requested_at = NULL WHERE lower(email) = ?", email);
        clearInvocations(mailer);
        json(post("/api/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        temporary = emailedPassword(email);
        studioLogin(email, temporary).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        // It replaced the password: other devices are logged out and the old password stops working.
        mvc.perform(get("/api/admin/account").header("Authorization", bearer(oldSession))).andExpect(status().isUnauthorized());
        studioLogin(email, "temporary-123").andExpect(status().isUnauthorized());
        // It is now their password (marked temporary) until they choose their own.
        studioLogin(email, temporary).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void temporaryPasswordsExpireAndDisabledLoginsGetNoEmail() throws Exception {
        String email = studio("expire-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test");
        json(post("/api/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        String temporary = emailedPassword(email);
        jdbc.update("UPDATE admin_users SET reset_expires_at = now() - interval '1 minute' WHERE lower(email) = ?", email);
        studioLogin(email, temporary).andExpect(status().isUnauthorized());

        String studioId = JsonPath.<java.util.List<String>>read(mvc.perform(get("/api/admin/studios")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString(),
                "$[?(@.email == '" + email + "')].id").getFirst();
        json(patch("/api/admin/studios/" + studioId), adminToken(), "{\"disabled\":true}").andExpect(status().isOk());
        jdbc.update("UPDATE admin_users SET reset_requested_at = NULL WHERE lower(email) = ?", email);
        clearInvocations(mailer);
        json(post("/api/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        verify(mailer, after(300).never()).send(eq(email), anyString(), anyString());
    }

    @Test
    void aCoupleResetsTheirPasswordByEmail() throws Exception {
        String eventId = eventId(createEvent("Reset Wedding", "LIVE"));
        String email = "reset-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        json(post("/api/admin/events/" + eventId + "/clients"), adminToken(),
                "{\"name\":\"Arun & Priya\",\"email\":\"" + email + "\",\"password\":\"temporary-123\"}").andExpect(status().isCreated());
        String oldSession = JsonPath.read(clientLogin(email, "temporary-123").andReturn().getResponse().getContentAsString(), "$.token");

        json(post("/api/client/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        String temporary = emailedPassword(email);
        clientLogin(email, temporary).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/client/me").header("Authorization", bearer(oldSession))).andExpect(status().isUnauthorized());
        clientLogin(email, "temporary-123").andExpect(status().isUnauthorized());

        // No email while McreatiK has the couple's gallery turned off.
        json(patch("/api/admin/events/" + eventId + "/access"), adminToken(), "{\"clientGallery\":false}").andExpect(status().isOk());
        jdbc.update("UPDATE event_clients SET reset_requested_at = NULL WHERE email = ?", email);
        clearInvocations(mailer);
        json(post("/api/client/auth/forgot-password"), null, "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
        verify(mailer, after(300).never()).send(eq(email), anyString(), anyString());
    }
}
