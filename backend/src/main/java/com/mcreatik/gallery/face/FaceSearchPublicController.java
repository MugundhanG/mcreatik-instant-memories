package com.mcreatik.gallery.face;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

import javax.imageio.ImageIO;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventService;

/** Guests' "Find my photos". The selfie lives in memory for this request only: it is never saved or logged. */
@RestController
@RequestMapping("/api/public/events/{slug}")
public class FaceSearchPublicController {

    static final long MAX_SELFIE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final EventService events;
    private final FaceSearchService faces;

    public FaceSearchPublicController(EventService events, FaceSearchService faces) {
        this.events = events;
        this.faces = faces;
    }

    @PostMapping(path = "/find-me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FaceSearchService.FindResult> findMe(@PathVariable String slug,
                                                               @RequestParam(value = "selfie", required = false) MultipartFile selfie,
                                                               HttpServletRequest request) throws IOException {
        Event event = events.publicEvent(slug);
        faces.requireSearchable(event);
        if (selfie == null || selfie.isEmpty()) {
            throw ApiException.badRequest("BAD_IMAGE", "Choose a photo of your face");
        }
        if (selfie.getSize() > MAX_SELFIE_BYTES) {
            throw tooLarge();
        }
        String type = selfie.getContentType() == null ? "" : selfie.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("BAD_IMAGE", "Use a JPEG, PNG or WebP photo");
        }
        byte[] image = readableImage(selfie.getBytes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(faces.find(event, image, request.getRemoteAddr()));
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SELFIE_TOO_LARGE", "That photo is too large (5 MB at most)");
    }

    /** JPEG and PNG go to the engine as they are; WebP is converted if this server can decode it. Anything else is refused. */
    private static byte[] readableImage(byte[] b) throws IOException {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return b;
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return b;
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                && b[10] == 'B' && b[11] == 'P') {
            var img = ImageIO.read(new ByteArrayInputStream(b));
            if (img != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                var rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                rgb.createGraphics().drawImage(img, 0, 0, java.awt.Color.WHITE, null);
                ImageIO.write(rgb, "jpg", out);
                return out.toByteArray();
            }
        }
        throw ApiException.badRequest("BAD_IMAGE", "We couldn't read that photo. Please try a JPEG or PNG.");
    }
}
