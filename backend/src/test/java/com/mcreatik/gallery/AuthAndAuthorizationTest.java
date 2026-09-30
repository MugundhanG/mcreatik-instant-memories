package com.mcreatik.gallery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mcreatik.gallery.auth.AdminUser;
import com.mcreatik.gallery.auth.AdminUserRepository;
import com.mcreatik.gallery.support.IntegrationTest;

class AuthAndAuthorizationTest extends IntegrationTest {

    @Autowired
    AdminUserRepository adminUsers;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void loginReturnsJwtForValidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ADMIN@mcreatik.test\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL));
    }

    @Test
    void loginRejectsWrongPasswordAndUnknownUser() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@mcreatik.test\",\"password\":\"whatever-123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminApiRequiresValidJwt() throws Exception {
        mvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void uploaderTokenCannotAccessAdminApi() throws Exception {
        String event = createEvent("Token Scope", "UPCOMING");
        String token = createUploader(eventId(event), "Camera 1");
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminJwtCannotAccessUploaderApi() throws Exception {
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(adminToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/uploader/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotSeeAnotherAdminsEvent() throws Exception {
        String event = createEvent("Private Wedding", "LIVE");
        if (adminUsers.findByEmail("second@mcreatik.test").isEmpty()) {
            adminUsers.save(new AdminUser("second@mcreatik.test", passwordEncoder.encode("second-password-1"), "Second"));
        }
        String other = login("second@mcreatik.test", "second-password-1");
        mvc.perform(get("/api/admin/events/" + eventId(event)).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/events/" + eventId(event) + "/stats").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/events/" + eventId(event) + "/uploaders").header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Sneaky\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/api/internal/secret")).andExpect(status().isForbidden());
    }
}
