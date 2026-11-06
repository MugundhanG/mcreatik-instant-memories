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
    private String studioId;

    @BeforeEach
    void studioLogin() throws Exception {
        String email = "brand-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens & Light\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"MONTHLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
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
    void guestsSeeTheStudiosContactNumber() throws Exception {
        // A 10-digit Indian mobile with spaces becomes +91…; guests get it with the event's brand.
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"098765 43210\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand.phone").value("+919876543210"));
        String slug = studioEventSlug();
        mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.brand.phone").value("+919876543210"));
        // International numbers are kept; nonsense is refused.
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+44 20 7946 0958\"}"))
                .andExpect(jsonPath("$.brand.phone").value("+442079460958"));
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"call me\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        // Leaving other fields out keeps it; a blank number removes it.
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Lens & Light\"}"))
                .andExpect(jsonPath("$.brand.phone").value("+442079460958"));
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"\"}"));
        mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.brand.phone").doesNotExist());
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

    @Test
    void studiosSizeThePosterLogoAndMcreatikSetsUpBrandingForThem() throws Exception {
        // The studio makes its poster logo bigger.
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"logoScale\":150}"))
                .andExpect(jsonPath("$.brand.logoScale").value(150));
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"logoScale\":500}"))
                .andExpect(status().isBadRequest());

        // McreatiK edits the studio's branding during onboarding; guests see it.
        mvc.perform(patch("/api/admin/studios/" + studioId + "/branding").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Lens & Light Studio\",\"instagram\":\"@lenslight.in\",\"logoScale\":80}"))
                .andExpect(jsonPath("$.name").value("Lens & Light Studio"))
                .andExpect(jsonPath("$.instagram").value("lenslight.in"))
                .andExpect(jsonPath("$.logoScale").value(80));
        mvc.perform(multipart("/api/admin/studios/" + studioId + "/logo")
                        .file(new MockMultipartFile("logo", "logo.png", "image/png", png()))
                        .header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.logoUrl").isNotEmpty());
        mvc.perform(get("/api/public/events/" + studioEventSlug()))
                .andExpect(jsonPath("$.brand.name").value("Lens & Light Studio"))
                .andExpect(jsonPath("$.brand.logoScale").value(80))
                .andExpect(jsonPath("$.brand.logoUrl").isNotEmpty());
        mvc.perform(get("/api/admin/studios").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$[?(@.id == '" + studioId + "')].brand.instagram").value("lenslight.in"));

        // Studios cannot edit other studios' branding.
        mvc.perform(patch("/api/admin/studios/" + studioId + "/branding").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instagram\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studiosPickTheirBoardDesign() throws Exception {
        mvc.perform(get("/api/admin/account").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.brand.boardTheme").value("cream"));
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"boardTheme\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand.boardTheme").value("dark"));
        mvc.perform(patch("/api/admin/account").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"boardTheme\":\"neon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_THEME"));
        // Event pages (and so the event cards) use the owner's design.
        mvc.perform(get("/api/public/events/" + studioEventSlug()))
                .andExpect(jsonPath("$.brand.boardTheme").value("dark"));
        // McreatiK can set it during onboarding.
        mvc.perform(patch("/api/admin/studios/" + studioId + "/branding").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"boardTheme\":\"white\"}"))
                .andExpect(jsonPath("$.boardTheme").value("white"));
    }

    @Test
    void theDashboardGetsTheLogoFileForPrintFiles() throws Exception {
        // No logo yet: nothing to serve.
        mvc.perform(get("/api/admin/account/logo").header("Authorization", bearer(studio))).andExpect(status().isNotFound());
        byte[] logo = png();
        mvc.perform(multipart("/api/admin/account/logo").file(new MockMultipartFile("logo", "logo.png", "image/png", logo))
                .header("Authorization", bearer(studio))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/account/logo").header("Authorization", bearer(studio)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(logo));

        // The event's cards use the owning studio's logo: the studio and McreatiK get it, another studio does not.
        String eventId = JsonPath.read(mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Logo test\",\"eventDate\":\"2026-11-21\",\"status\":\"UPCOMING\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/admin/events/" + eventId + "/logo").header("Authorization", bearer(studio)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(logo));
        mvc.perform(get("/api/admin/events/" + eventId + "/logo").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
        String other = "other-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other\",\"email\":\"" + other + "\",\"password\":\"temporary-123\",\"plan\":\"MONTHLY\"}"))
                .andExpect(status().isCreated());
        int n = IPS.incrementAndGet();
        String otherToken = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + other + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.88.1." + n);
                            return r;
                        }))
                .andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(get("/api/admin/events/" + eventId + "/logo").header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/account/logo")).andExpect(status().isUnauthorized());
    }
}
