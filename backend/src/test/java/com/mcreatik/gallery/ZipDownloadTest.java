package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** One client login per event, and many photos downloaded at once as a ZIP. */
class ZipDownloadTest extends IntegrationTest {

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder r) throws Exception {
        return r.header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON);
    }

    private String json(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r).andReturn().getResponse().getContentAsString();
    }

    private List<String> zipEntries(String path) throws Exception {
        byte[] zip = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                assertThat(in.readAllBytes()).isNotEmpty();
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    void oneClientLoginPerEventAndEveryoneDownloadsSeveralPhotosAsOneZip() throws Exception {
        String eventId = eventId(createEvent("Arun Priya Wedding", "LIVE"));
        // An event from before the galleries were split: the couple sees every photo.
        jdbc.update("UPDATE events SET split_galleries = false WHERE id = ?", java.util.UUID.fromString(eventId));
        String token = createUploader(eventId, "Camera 1");
        List<String> photos = List.of(uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600)),
                uploadPhoto(token, "IMG_1.JPG", jpeg(800, 601)), uploadPhoto(token, "IMG_3.JPG", jpeg(800, 602)));
        processAll();

        String email = "zip-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        mvc.perform(admin(post("/api/admin/events/" + eventId + "/clients"))
                        .content("{\"name\":\"Arun & Priya\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"phone\":\"98765 43210\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("98765 43210"));
        // A second login for the same event is refused: the couple shares one.
        mvc.perform(admin(post("/api/admin/events/" + eventId + "/clients"))
                        .content("{\"name\":\"Someone\",\"email\":\"second-" + email + "\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_EXISTS"));
        mvc.perform(admin(post("/api/admin/events/" + eventId + "/clients"))
                .content("{\"name\":\"X\",\"email\":\"bad-" + email + "\",\"password\":\"temporary-123\",\"phone\":\"call me\"}"))
                .andExpect(status().isBadRequest());

        // The studio downloads two photos; same camera file names get numbered.
        String prepared = json(admin(post("/api/admin/events/" + eventId + "/downloads"))
                .content("{\"photoIds\":[\"" + photos.get(0) + "\",\"" + photos.get(1) + "\"]}"));
        assertThat(JsonPath.<Integer>read(prepared, "$.photos")).isEqualTo(2);
        assertThat(JsonPath.<String>read(prepared, "$.fileName")).isEqualTo("Arun-Priya-Wedding-photos.zip");
        // The app shows a percentage: waiting until the link opens, 100% when every photo is in the ZIP.
        String progress = JsonPath.read(prepared, "$.progressPath");
        mvc.perform(get(progress)).andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.percent").value(0));
        assertThat(zipEntries(JsonPath.read(prepared, "$.path"))).containsExactly("IMG_1.JPG", "IMG_1 (2).JPG");
        mvc.perform(get(progress)).andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.done").value(2)).andExpect(jsonPath("$.percent").value(100));

        // The couple selects two photos and downloads their album; the studio can download the same selection.
        String client = JsonPath.read(mvc.perform(post("/api/client/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                .with(r -> {
                    r.setRemoteAddr("10.98.0.1");
                    return r;
                })).andReturn().getResponse().getContentAsString(), "$.token");
        for (String id : List.of(photos.get(0), photos.get(2))) {
            mvc.perform(put("/api/client/photos/" + id + "/selected").header("Authorization", bearer(client))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}")).andExpect(status().isOk());
        }
        String album = json(post("/api/client/downloads").header("Authorization", bearer(client))
                .contentType(MediaType.APPLICATION_JSON).content("{\"album\":true}"));
        assertThat(zipEntries(JsonPath.read(album, "$.path"))).containsExactly("IMG_1.JPG", "IMG_3.JPG");
        String studioAlbum = json(admin(post("/api/admin/events/" + eventId + "/downloads")).content("{\"album\":true}"));
        assertThat(zipEntries(JsonPath.read(studioAlbum, "$.path"))).containsExactly("IMG_1.JPG", "IMG_3.JPG");

        // A photo hidden from guests is left out of the couple's downloads, not the studio's.
        mvc.perform(admin(patch("/api/admin/photos/" + photos.get(2))).content("{\"hidden\":true}")).andExpect(status().isOk());
        String all = json(post("/api/client/downloads").header("Authorization", bearer(client))
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"all\"}"));
        assertThat(JsonPath.<Integer>read(all, "$.photos")).isEqualTo(2);
        String studioAll = json(admin(post("/api/admin/events/" + eventId + "/downloads"))
                .content("{\"photoIds\":[\"" + String.join("\",\"", photos) + "\"]}"));
        assertThat(JsonPath.<Integer>read(studioAll, "$.photos")).isEqualTo(3);

        // A client cannot download another event's photos; unknown links are refused.
        String other = eventId(createEvent("Other", "LIVE"));
        String otherPhoto = uploadPhoto(createUploader(other, "C"), "X.JPG", jpeg(640, 480));
        processAll();
        mvc.perform(post("/api/client/downloads").header("Authorization", bearer(client))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[\"" + otherPhoto + "\"]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/downloads/zip_nope")).andExpect(status().isGone());
        mvc.perform(get("/api/public/downloads/zip_nope/progress")).andExpect(status().isGone());
        mvc.perform(admin(post("/api/admin/events/" + eventId + "/downloads")).content("{\"photoIds\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
