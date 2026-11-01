package com.mcreatik.gallery.download;

import java.io.IOException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

/** Opens a prepared ZIP link (the random link is the permission; it expires after 30 minutes). */
@RestController
public class ZipDownloadController {

    private final ZipDownloads downloads;

    public ZipDownloadController(ZipDownloads downloads) {
        this.downloads = downloads;
    }

    @GetMapping("/api/public/downloads/{token}")
    public void download(@PathVariable String token, HttpServletResponse response) throws IOException {
        ZipDownloads.Job job = downloads.job(token);
        downloads.acquire();
        try {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(job.fileName()).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            downloads.write(job, response.getOutputStream());
        } finally {
            downloads.release();
        }
    }
}
