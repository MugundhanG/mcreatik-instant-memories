package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** McreatiK (super admin) creates studio logins; each studio sees only its own events. */
class StudioAccountsTest extends IntegrationTest {

    private String email;
    private String studioId;

    @BeforeEach
    void createStudio() throws Exception {
        email = "studio-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        String body = mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDIO"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        studioId = JsonPath.read(body, "$.id");
    }

    private static final java.util.concurrent.atomic.AtomicInteger IPS = new java.util.concurrent.atomic.AtomicInteger();

    /** Each login from its own address, so these tests never trip the per-IP login brute-force limit. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String user, String password) {
        int n = IPS.incrementAndGet();
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user + "\",\"password\":\"" + password + "\"}")
                .with(req -> {
                    req.setRemoteAddr("10.77." + (n / 250) + "." + (n % 250 + 1));
                    return req;
                });
    }

    private String studioLogin(String password) throws Exception {
        return JsonPath.read(mvc.perform(loginRequest(email, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String createEventAs(String token, String name, String extra) throws Exception {
        return mvc.perform(post("/api/admin/events").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"eventDate\":\"2026-11-01\"" + extra + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void studioSeesOnlyItsOwnEventsAndSuperAdminSeesAll() throws Exception {
        String studio = studioLogin("temporary-123");
        String studioEvent = eventId(createEventAs(studio, "Studio Wedding", ""));
        String mine = eventId(createEvent("McreatiK Wedding", "LIVE"));

        List<String> studioList = JsonPath.read(mvc.perform(get("/api/admin/events").header("Authorization", bearer(studio)))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        assertThat(studioList).containsExactly(studioEvent);
        mvc.perform(get("/api/admin/events/" + mine).header("Authorization", bearer(studio)))
                .andExpect(status().isNotFound());

        // McreatiK sees every studio's events, with the studio's name, and can open them for support.
        String all = mvc.perform(get("/api/admin/events").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(all, "$[*].id")).contains(studioEvent, mine);
        assertThat(JsonPath.<List<String>>read(all, "$[?(@.id == '" + studioEvent + "')].ownerName"))
                .containsExactly("Lens & Light");
        mvc.perform(get("/api/admin/events/" + studioEvent + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminCreatesEventsForAStudioButStudiosCannotForOthers() throws Exception {
        String created = createEventAs(adminToken(), "Made for studio", ",\"ownerId\":\"" + studioId + "\"");
        assertThat(JsonPath.<String>read(created, "$.ownerName")).isEqualTo("Lens & Light");
        String studio = studioLogin("temporary-123");
        mvc.perform(get("/api/admin/events/" + eventId(created)).header("Authorization", bearer(studio)))
                .andExpect(status().isOk());

        List<String> superIds = JsonPath.read(mvc.perform(get("/api/admin/studios").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString(), "$[?(@.role == 'SUPER_ADMIN')].id");
        String superId = superIds.getFirst();
        mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky\",\"eventDate\":\"2026-11-01\",\"ownerId\":\"" + superId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyTheSuperAdminManagesStudios() throws Exception {
        String studio = studioLogin("temporary-123");
        mvc.perform(get("/api/admin/studios").header("Authorization", bearer(studio))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"email\":\"x@client.test\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/studios").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + email + "')].name").value("Lens & Light"));
    }

    @Test
    void studioReplacesTheTemporaryPassword() throws Exception {
        String studio = studioLogin("temporary-123");
        mvc.perform(get("/api/admin/account").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(post("/api/admin/account/password").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"my-own-password\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/account/password").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"temporary-123\",\"newPassword\":\"my-own-password\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(loginRequest(email, "my-own-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDIO"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void disablingAStudioLocksItOutAtOnce() throws Exception {
        String studio = studioLogin("temporary-123");
        mvc.perform(patch("/api/admin/studios/" + studioId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"))
                .andExpect(status().isOk());
        // The token it already had stops working, and it cannot log in again.
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(studio)))
                .andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(email, "temporary-123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }
}
