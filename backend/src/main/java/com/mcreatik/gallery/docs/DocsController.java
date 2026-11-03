package com.mcreatik.gallery.docs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
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
 * <p>Pages come from a fixed list; the slug in the URL is only ever a map key, never a file path.
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
}
