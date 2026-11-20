package com.mcreatik.gallery.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.processing.PhotoProcessingWorker;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String ADMIN_EMAIL = "admin@mcreatik.test";
    public static final String ADMIN_PASSWORD = "correct-horse-battery";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PhotoProcessingWorker worker;

    private static String cachedAdminToken;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE photos, uploaders, events CASCADE");
    }

    private static final java.util.concurrent.atomic.AtomicInteger LOGIN_IPS = new java.util.concurrent.atomic.AtomicInteger();

    protected String adminToken() throws Exception {
        if (cachedAdminToken == null) {
            cachedAdminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        }
        return cachedAdminToken;
    }

    /** Each login comes from its own address, so the shared brute-force limit never depends on test order or speed. */
    protected String login(String email, String password) throws Exception {
        int n = LOGIN_IPS.incrementAndGet();
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("10.250." + (n / 250) % 250 + "." + (n % 250 + 1));
                            return req;
                        }))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.token");
    }

    /**
     * Gives a studio an earlier confirmed payment, so its UPI payments count at once (a new studio's are held until
     * McreatiK approves them).
     */
    protected void trustStudio(String studioId) {
        jdbc.update("""
                INSERT INTO event_payments (id, studio_id, events, unit_price, amount, utr, status, reviewed_at)
                VALUES (gen_random_uuid(), ?::uuid, 0, 0, 0, ?, 'APPROVED', now())""",
                studioId, "TRUST" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected String createEvent(String name, String status) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/events").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"eventDate\":\"2026-10-10\",\"status\":\"" + status + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return r.getResponse().getContentAsString();
    }

    protected String eventId(String eventJson) {
        return JsonPath.read(eventJson, "$.id");
    }

    protected String slug(String eventJson) {
        return JsonPath.read(eventJson, "$.slug");
    }

    /** Registers an uploader and returns its plaintext token. */
    protected String createUploader(String eventId, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/events/" + eventId + "/uploaders")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.token");
    }

    /** A unique, valid JPEG. */
    public static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        g.setColor(new Color(rnd.nextInt(0xFFFFFF)));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(rnd.nextInt(0xFFFFFF)));
        g.fillOval(rnd.nextInt(width / 2), rnd.nextInt(height / 2), width / 3, height / 3);
        g.dispose();
        img.setRGB(rnd.nextInt(width), rnd.nextInt(height), rnd.nextInt());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    protected static String uploadRequestJson(String fileName, byte[] bytes) {
        return "{\"fileName\":\"" + fileName + "\",\"fileSize\":" + bytes.length
                + ",\"mimeType\":\"image/jpeg\",\"checksumSha256\":\"" + Tokens.sha256Hex(bytes) + "\"}";
    }

    /** Runs the full uploader protocol through MockMvc and returns the photo id. */
    protected String uploadPhoto(String uploaderToken, String fileName, byte[] bytes) throws Exception {
        MvcResult session = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(uploaderToken))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson(fileName, bytes)))
                .andExpect(status().isOk()).andReturn();
        String json = session.getResponse().getContentAsString();
        String photoId = JsonPath.read(json, "$.photoId");
        if ("UPLOAD".equals(JsonPath.read(json, "$.outcome"))) {
            putToStorage(JsonPath.read(json, "$.upload.url"), bytes);
            mvc.perform(post("/api/uploader/uploads/" + photoId + "/complete")
                            .header("Authorization", bearer(uploaderToken)))
                    .andExpect(status().isOk());
        }
        return photoId;
    }

    protected void putToStorage(String presignedUrl, byte[] bytes) throws Exception {
        URI uri = URI.create(presignedUrl);
        mvc.perform(put(uri.getRawPath() + "?" + uri.getRawQuery()).contentType("image/jpeg").content(bytes))
                .andExpect(status().isOk());
    }

    protected void processAll() {
        while (worker.processNext()) {
            // drain
        }
    }

    protected String publicPhotos(String slug) throws Exception {
        return mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
