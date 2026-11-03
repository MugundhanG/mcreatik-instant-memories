package com.mcreatik.gallery.docs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.AdminAccess;
import com.mcreatik.gallery.common.ApiException;

/**
 * McreatiK's internal documentation (architecture, API, security findings), shown in the dashboard to the super
 * admin only. It is served from the backend after the role check instead of being built into the website, because
 * every website file is public and the security notes must not be.
 * <p>Pages come from a fixed list; the slug in the URL is only ever a map key, never a file path. Screenshots and
 * illustrations live in {@code docs/images/}; an image name must match {@link #IMAGE_NAME} (no slashes or dots
 * beyond the extension), so it can never reach another classpath file.
 */
@RestController
@RequestMapping("/api/admin/docs")
public class DocsController {

    /** The day the documentation was last checked against the code. */
    static final String REVIEWED_ON = "2026-11-03";

    record Page(String slug, String title, String group) {
    }

    static final List<Page> PAGES = List.of(
            new Page("overview", "Overview", "Getting started"),
            new Page("architecture", "Architecture", "Getting started"),
            new Page("sop-admin", "McreatiK admin", "SOPs"),
            new Page("sop-studio", "Studios", "SOPs"),
            new Page("sop-couple", "Couples (clients)", "SOPs"),
            new Page("sop-guest", "Guests", "SOPs"),
            new Page("guide-uploader", "McreatiK Uploader", "Camera & uploader guides"),
            new Page("guide-canon", "Canon EOS R6 Mark II", "Camera & uploader guides"),
            new Page("guide-sony", "Sony α7 IV", "Camera & uploader guides"),
            new Page("guide-event-day", "Event-day checklist", "Camera & uploader guides"),
            new Page("frontend", "Frontend", "Codebase"),
            new Page("backend", "Backend", "Codebase"),
            new Page("database", "Database", "Codebase"),
            new Page("api", "API reference", "Codebase"),
            new Page("journeys", "User journeys", "Product"),
            new Page("qa-how-to-use", "How to use these answers", "Instant Memories Q&A"),
            new Page("qa-guests", "Guests", "Instant Memories Q&A"),
            new Page("qa-couples", "Couples", "Instant Memories Q&A"),
            new Page("qa-studios-product", "Studios: product", "Instant Memories Q&A"),
            new Page("qa-studios-buying", "Studios: buying and trust", "Instant Memories Q&A"),
            new Page("qa-tricky", "Tricky questions", "Instant Memories Q&A"),
            new Page("security", "Security and performance", "Operations"),
            new Page("deployment", "Deployment", "Operations"),
            new Page("findings", "Findings and next actions", "Reference"),
            new Page("glossary", "Glossary", "Reference"));

    /** Lower-case letters, digits and dashes, then one known extension. */
    static final Pattern IMAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}\\.(png|jpg|webp|svg)");

    public record PageSummary(String slug, String title, String group) {
    }

    public record PageContent(String slug, String title, String group, String markdown, String reviewedOn) {
    }

    private final AdminAccess access;
    private final Map<String, PageContent> pages;

    public DocsController(AdminAccess access) {
        this.access = access;
        Map<String, PageContent> loaded = new LinkedHashMap<>();
        for (Page p : PAGES) {
            loaded.put(p.slug(), new PageContent(p.slug(), p.title(), p.group(), read(p.slug()), REVIEWED_ON));
        }
        this.pages = Map.copyOf(loaded);
    }

    private static String read(String slug) {
        ClassPathResource file = new ClassPathResource("docs/" + slug + ".md");
        try (InputStream in = file.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("Documentation page is empty: docs/" + slug + ".md");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Documentation page is missing: docs/" + slug + ".md", e);
        }
    }

    @GetMapping
    public ResponseEntity<List<PageSummary>> list(Authentication auth) {
        access.requireSuperAdmin(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PAGES.stream().map(p -> new PageSummary(p.slug(), p.title(), p.group())).toList());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PageContent> page(Authentication auth, @PathVariable String slug) {
        access.requireSuperAdmin(auth);
        PageContent page = pages.get(slug);
        if (page == null) {
            throw ApiException.notFound("Page");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    /** A screenshot or illustration used by a page. Private to the super admin like the pages themselves. */
    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> image(Authentication auth, @PathVariable String name) {
        access.requireSuperAdmin(auth);
        if (!IMAGE_NAME.matcher(name).matches()) {
            throw ApiException.notFound("Image");
        }
        ClassPathResource file = new ClassPathResource("docs/images/" + name);
        if (!file.exists()) {
            throw ApiException.notFound("Image");
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                    .contentType(mediaType(name))
                    // An SVG opened on its own must not run anything.
                    .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                    .body(in.readAllBytes());
        } catch (IOException e) {
            throw ApiException.notFound("Image");
        }
    }

    private static MediaType mediaType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "svg" -> MediaType.valueOf("image/svg+xml");
            default -> MediaType.valueOf("image/webp");
        };
    }
}
