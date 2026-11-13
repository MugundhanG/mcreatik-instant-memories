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

/** McreatiK sees every page; a studio sees only its guides, and the internal pages don't exist for it. */
class DocsTest extends IntegrationTest {

    static final java.util.List<String> STUDIO_PAGES = java.util.List.of("sop-studio", "sop-couple", "sop-guest",
            "guide-uploader", "guide-canon", "guide-sony", "guide-event-day", "guide-event-day-problems", "qa-guests", "qa-couples",
            "qa-studios-product");

    private String studioLogin() throws Exception {
        String email = "docs-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}"))
                .andExpect(status().isCreated());
        return login(email, "temporary-123");
    }

    private static java.util.Set<String> imagesOf(String markdown) {
        java.util.Set<String> used = new java.util.TreeSet<>();
        var m = java.util.regex.Pattern.compile("\\]\\(images/([^)]+)\\)").matcher(markdown);
        while (m.find()) used.add(m.group(1));
        return used;
    }

    @Test
    void loggedOutCallersGetNothing() throws Exception {
        mvc.perform(get("/api/admin/docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/docs/security")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/docs/sop-studio")).andExpect(status().isUnauthorized());
    }

    @Test
    void aStudioSeesOnlyItsGuidesAndTheirImages() throws Exception {
        String studio = studioLogin();
        String list = mvc.perform(get("/api/admin/docs").header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> slugs = JsonPath.read(list, "$[*].slug");
        assertThat(slugs).containsExactlyInAnyOrderElementsOf(STUDIO_PAGES);

        java.util.Set<String> studioImages = new java.util.TreeSet<>();
        for (String slug : slugs) {
            String page = mvc.perform(get("/api/admin/docs/" + slug).header("Authorization", bearer(studio)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String md = JsonPath.read(page, "$.markdown");
            studioImages.addAll(imagesOf(md));
            // A studio guide never links to a page the studio can't open.
            var link = java.util.regex.Pattern.compile("\\]\\(/admin/docs/([a-z0-9-]+)").matcher(md);
            while (link.find()) assertThat(STUDIO_PAGES).as(slug + " links " + link.group(1)).contains(link.group(1));
        }
        assertThat(studioImages).isNotEmpty();
        for (String name : studioImages) {
            mvc.perform(get("/api/admin/docs/images/" + name).header("Authorization", bearer(studio)))
                    .andExpect(status().isOk());
        }

        for (String internal : new String[] {"security", "api", "sop-admin", "qa-tricky", "qa-studios-buying",
                "deployment", "findings", "overview"}) {
            mvc.perform(get("/api/admin/docs/" + internal).header("Authorization", bearer(studio)))
                    .andExpect(status().isNotFound());
        }
        String security = mvc.perform(get("/api/admin/docs/sop-admin").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> adminOnly = imagesOf(JsonPath.read(security, "$.markdown"));
        adminOnly.removeAll(studioImages);
        assertThat(adminOnly).as("sop-admin has images studios don't use").isNotEmpty();
        mvc.perform(get("/api/admin/docs/images/" + adminOnly.iterator().next()).header("Authorization", bearer(studio)))
                .andExpect(status().isNotFound());
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
        assertThat(slugs).hasSize(26).contains("api", "security", "glossary", "qa-guests", "qa-tricky", "sop-admin", "guide-sony");
        java.util.List<String> qaGroups = JsonPath.read(list, "$[?(@.slug == 'qa-guests')].group");
        assertThat(qaGroups).containsExactly("Instant Memories Q&A");
        java.util.List<String> forStudios = JsonPath.read(list, "$[?(@.studio == true)].slug");
        assertThat(forStudios).containsExactlyInAnyOrderElementsOf(STUDIO_PAGES);

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
    void everyImageAPageUsesExists() throws Exception {
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
        mvc.perform(get("/api/admin/docs/images/" + "a-docs.webp").header("Authorization", bearer(login(email, "temporary-123"))))
                .andExpect(status().isNotFound());
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
