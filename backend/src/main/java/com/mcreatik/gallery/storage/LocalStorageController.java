package com.mcreatik.gallery.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Emulates R2 presigned PUT/GET and the public media domain when STORAGE_TYPE=local. */
@RestController
@ConditionalOnProperty(name = "gallery.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageController {

    private final LocalStorageService storage;

    public LocalStorageController(LocalStorageService storage) {
        this.storage = storage;
    }

    @PutMapping("/local-storage/upload/{*key}")
    public ResponseEntity<Void> upload(@PathVariable String key,
                                       @RequestParam long expires,
                                       @RequestParam long length,
                                       @RequestParam String sig,
                                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                       HttpServletRequest request) throws IOException {
        String cleanKey = key.substring(1);
        if (!storage.verifyUpload(cleanKey, expires, normalizeMediaType(contentType), length, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request.getContentLengthLong() >= 0 && request.getContentLengthLong() != length) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            storage.writeStream(StorageArea.ORIGINALS, cleanKey, request.getInputStream(), length);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok().build();
    }

    /** "image/jpeg; charset=UTF-8" and "IMAGE/JPEG" both sign as "image/jpeg". */
    static String normalizeMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return (type.getType() + "/" + type.getSubtype()).toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @GetMapping("/local-storage/media/{*key}")
    public ResponseEntity<Resource> media(@PathVariable String key) {
        Path path = storage.resolve(StorageArea.MEDIA, key.substring(1));
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(new FileSystemResource(path));
    }

    @GetMapping("/local-storage/originals/{*key}")
    public ResponseEntity<Resource> original(@PathVariable String key, @RequestParam long expires,
                                             @RequestParam String sig,
                                             @RequestParam(defaultValue = "photo.jpg") String name) {
        String cleanKey = key.substring(1);
        if (!storage.verifyDownload(cleanKey, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path path = storage.resolve(StorageArea.ORIGINALS, cleanKey);
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
