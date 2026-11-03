package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** The internal documentation is for McreatiK only: studios and logged-out callers never get it. */
class DocsTest extends IntegrationTest {

    @Test
    void onlyMcreatikCanReadTheDocumentation() throws Exception {
        mvc.perform(get("/api/admin/docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/docs/security")).andExpect(status().isUnauthorized());

        String email = "docs-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}"))
                .andExpect(status().isCreated());
        String studio = login(email, "temporary-123");
        mvc.perform(get("/api/admin/docs").header("Authorization", bearer(studio))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/docs/security").header("Authorization", bearer(studio)))
                .andExpect(status().isForbidden());
    }

    @Test
    void mcreatikGetsEveryPageInOrder() throws Exception {
        String list = mvc.perform(get("/api/admin/docs").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].slug").value("overview"))
                .andExpect(jsonPath("$[0].group").value("Getting started"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> slugs = JsonPath.read(list, "$[*].slug");
        assertThat(slugs).hasSize(25).contains("api", "security", "glossary", "qa-guests", "qa-tricky", "sop-admin", "guide-sony");
        java.util.List<String> qaGroups = JsonPath.read(list, "$[?(@.slug == 'qa-guests')].group");
        assertThat(qaGroups).containsExactly("Instant Memories Q&A");

        for (String slug : slugs) {
            String page = mvc.perform(get("/api/admin/docs/" + slug).header("Authorization", bearer(adminToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value(slug))
                    .andExpect(jsonPath("$.reviewedOn").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            String markdown = JsonPath.read(page, "$.markdown");
            assertThat(markdown).as(slug).startsWith("# ");
        }
    }

    @Test
    void everyImageAPageUsesExistsAndIsSuperAdminOnly() throws Exception {
        java.util.regex.Pattern ref = java.util.regex.Pattern.compile("\\]\\(images/([^)]+)\\)");
        java.util.Set<String> used = new java.util.TreeSet<>();
        String list = mvc.perform(get("/api/admin/docs").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> slugs = JsonPath.read(list, "$[*].slug");
        for (String slug : slugs) {
            String page = mvc.perform(get("/api/admin/docs/" + slug).header("Authorization", bearer(adminToken())))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String md = JsonPath.read(page, "$.markdown");
            var m = ref.matcher(md);
            while (m.find()) used.add(m.group(1));
        }
        assertThat(used).isNotEmpty();
        for (String name : used) {
            mvc.perform(get("/api/admin/docs/images/" + name).header("Authorization", bearer(adminToken())))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'"));
        }
        String one = used.iterator().next();
        mvc.perform(get("/api/admin/docs/images/" + one)).andExpect(status().isUnauthorized());
        String email = "img-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Images\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/docs/images/" + one).header("Authorization", bearer(login(email, "temporary-123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void imageNamesCannotReachOtherFiles() throws Exception {
        for (String bad : new String[] {"..%2Fsecurity.md", "nope.webp", "Shot.PNG", "a.gif", "%2Fetc%2Fpasswd"}) {
            mvc.perform(get("/api/admin/docs/images/" + bad).header("Authorization", bearer(adminToken())))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void unknownPagesAndPathTricksAreNotFound() throws Exception {
        mvc.perform(get("/api/admin/docs/nope").header("Authorization", bearer(adminToken())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/admin/docs/..%2Fapplication").header("Authorization", bearer(adminToken())))
                .andExpect(status().is4xxClientError());
    }
}
