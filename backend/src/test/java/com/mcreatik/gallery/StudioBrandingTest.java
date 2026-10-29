package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** A studio's own name, logo and Instagram on its galleries; McreatiK's events keep McreatiK's design. */
class StudioBrandingTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private String studio;

    @BeforeEach
    void studioLogin() throws Exception {
        String email = "brand-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isCreated());
        int n = IPS.incrementAndGet();
        studio = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.88.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private String studioEventSlug() throws Exception {
        String json = mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Karthik & Divya\",\"eventDate\":\"2026-11-20\",\"status\":\"LIVE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return slug(json);
    }

    @Test
    void guestsSeeTheStudiosOwnBrand() throws Exception {
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Lens & Light Studio\",\"instagram\":\"https://www.instagram.com/LensLight.in/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand.instagram").value("lenslight.in"));
        String logoUrl = JsonPath.read(mvc.perform(multipart("/api/admin/account/logo")
                        .file(new MockMultipartFile("logo", "logo.png", "image/png", png()))
                        .header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.brand.logoUrl");
        assertThat(logoUrl).contains("branding/").endsWith(".png");

        String slug = studioEventSlug();
        mvc.perform(get("/api/public/events/" + slug))
                .andExpect(jsonPath("$.brand.name").value("Lens & Light Studio"))
                .andExpect(jsonPath("$.brand.instagram").value("lenslight.in"))
                .andExpect(jsonPath("$.brand.logoUrl").value(logoUrl))
                .andExpect(jsonPath("$.brand.mcreatik").value(false));

        // Removing the logo and the Instagram leaves the studio name only.
        mvc.perform(delete("/api/admin/account/logo").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.brand.logoUrl").doesNotExist());
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"instagram\":\"\"}"));
        mvc.perform(get("/api/public/events/" + slug))
                .andExpect(jsonPath("$.brand.instagram").doesNotExist())
                .andExpect(jsonPath("$.brand.logoUrl").doesNotExist());
    }

    @Test
    void mcreatikEventsKeepTheMcreatikDesign() throws Exception {
        String slug = slug(createEvent("Own event", "LIVE"));
        mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.brand.mcreatik").value(true));
    }

    @Test
    void rejectsBadInstagramAndNonImageLogos() throws Exception {
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instagram\":\"not a handle!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INSTAGRAM"));
        mvc.perform(multipart("/api/admin/account/logo")
                        .file(new MockMultipartFile("logo", "logo.png", "image/png", "<svg/>".getBytes()))
                        .header("Authorization", bearer(studio)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"));
    }
}
