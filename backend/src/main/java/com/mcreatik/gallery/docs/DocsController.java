package com.mcreatik.gallery.docs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
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
import com.mcreatik.gallery.auth.AdminUser;
import com.mcreatik.gallery.common.ApiException;

/**
 * The dashboard documentation. The McreatiK super admin sees every page (architecture, API, security findings);
 * a studio sees only the guides written for studios and their clients ({@link Page#studio()}), and every other
 * page and image is "not found" for it, so a studio can't even tell which internal pages exist. Pages are served
 * from the backend after this check instead of being built into the website, because every website file is
 * public and the security notes must not be.
 * <p>Pages come from a fixed list; the slug in the URL is only ever a map key, never a file path. Screenshots and
 * illustrations live in {@code docs/images/}; an image name must match {@link #IMAGE_NAME} (no slashes or dots
 * beyond the extension), so it can never reach another classpath file.
 */
@RestController
@RequestMapping("/api/admin/docs")
public class DocsController {

    /** The day the documentation was last checked against the code. */
    static final String REVIEWED_ON = "2026-11-13";

    /** One page; {@code studio} pages are also shown to studio logins (and are written for them). */
    record Page(String slug, String title, String group, boolean studio) {
        Page(String slug, String title, String group) {
            this(slug, title, group, false);
        }
    }

    static final List<Page> PAGES = List.of(
            new Page("overview", "Overview", "Getting started"),
            new Page("architecture", "Architecture", "Getting started"),
            new Page("sop-admin", "McreatiK admin", "How-to guides"),
            new Page("sop-studio", "Studios", "How-to guides", true),
            new Page("sop-couple", "Couples (clients)", "How-to guides", true),
            new Page("sop-guest", "Guests", "How-to guides", true),
            new Page("guide-uploader", "McreatiK Instant Memories Uploader", "Camera & uploader guides", true),
            new Page("guide-canon", "Canon EOS R6 Mark II", "Camera & uploader guides", true),
            new Page("guide-sony", "Sony α7 IV", "Camera & uploader guides", true),
            new Page("guide-event-day", "Event-day checklist", "Camera & uploader guides", true),
            new Page("guide-event-day-problems", "Event-day problem playbook", "Camera & uploader guides", true),
            new Page("frontend", "Frontend", "Codebase"),
            new Page("backend", "Backend", "Codebase"),
            new Page("database", "Database", "Codebase"),
            new Page("api", "API reference", "Codebase"),
            new Page("journeys", "User journeys", "Product"),
            new Page("qa-how-to-use", "How to use these answers", "Instant Memories Q&A"),
            new Page("qa-guests", "Guests", "Instant Memories Q&A", true),
            new Page("qa-couples", "Couples", "Instant Memories Q&A", true),
            new Page("qa-studios-product", "Studios: product", "Instant Memories Q&A", true),
            new Page("qa-studios-buying", "Studios: buying and trust", "Instant Memories Q&A"),
            new Page("qa-tricky", "Tricky questions", "Instant Memories Q&A"),
            new Page("security", "Security and performance", "Operations"),
            new Page("deployment", "Deployment", "Operations"),
            new Page("findings", "Findings and next actions", "Reference"),
            new Page("glossary", "Glossary", "Reference"));

    static final Set<String> STUDIO_SLUGS = Set.copyOf(PAGES.stream().filter(Page::studio).map(Page::slug).toList());

    /** Lower-case letters, digits and dashes, then one known extension. */
    static final Pattern IMAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}\\.(png|jpg|webp|svg)");

    public record PageSummary(String slug, String title, String group, boolean studio) {
    }

    /** How a page links an image: {@code ](images/name.webp)}. */
    static final Pattern IMAGE_REF = Pattern.compile("\\]\\(images/([^)\\s]+)\\)");

    public record PageContent(String slug, String title, String group, String markdown, String reviewedOn) {
    }

    private final AdminAccess access;
    private final Map<String, PageContent> pages;
    /** Images used by at least one studio page: the only images a studio may load. */
    private final Set<String> studioImages;

    public DocsController(AdminAccess access) {
        this.access = access;
        Map<String, PageContent> loaded = new LinkedHashMap<>();
        for (Page p : PAGES) {
            loaded.put(p.slug(), new PageContent(p.slug(), p.title(), p.group(), read(p.slug()), REVIEWED_ON));
        }
        this.pages = Map.copyOf(loaded);
        Set<String> images = new HashSet<>();
        for (Page p : PAGES) {
            if (p.studio()) {
                Matcher m = IMAGE_REF.matcher(pages.get(p.slug()).markdown());
                while (m.find()) {
                    images.add(m.group(1));
                }
            }
        }
        this.studioImages = Set.copyOf(images);
    }

    private static List<Page> visibleTo(AdminUser user) {
        return user.isSuperAdmin() ? PAGES : PAGES.stream().filter(Page::studio).toList();
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
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(visibleTo(access.current(auth)).stream()
                        .map(p -> new PageSummary(p.slug(), p.title(), p.group(), p.studio())).toList());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PageContent> page(Authentication auth, @PathVariable String slug) {
        AdminUser user = access.current(auth);
        PageContent page = pages.get(slug);
        boolean visible = page != null && (user.isSuperAdmin() || STUDIO_SLUGS.contains(slug));
        if (!visible) {
            throw ApiException.notFound("Page");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    /** A screenshot or illustration used by a page; a studio gets only the images its own pages use. */
    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> image(Authentication auth, @PathVariable String name) {
        AdminUser user = access.current(auth);
        if (!IMAGE_NAME.matcher(name).matches() || (!user.isSuperAdmin() && !studioImages.contains(name))) {
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
