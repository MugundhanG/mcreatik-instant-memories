package com.mcreatik.gallery.storage;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.config.GalleryProperties;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Cloudflare R2 through its S3-compatible API. */
@Service
@ConditionalOnProperty(name = "gallery.storage.type", havingValue = "r2")
public class R2StorageService implements StorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final GalleryProperties.Storage.R2 config;

    public R2StorageService(GalleryProperties properties) {
        this.config = properties.storage().r2();
        requireSet(config.accountId(), "R2_ACCOUNT_ID");
        requireSet(config.accessKeyId(), "R2_ACCESS_KEY_ID");
        requireSet(config.secretAccessKey(), "R2_SECRET_ACCESS_KEY");
        requireSet(config.mediaPublicBaseUrl(), "R2_MEDIA_PUBLIC_BASE_URL");

        URI endpoint = URI.create("https://" + config.accountId() + ".r2.cloudflarestorage.com");
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .httpClient(UrlConnectionHttpClient.create())
                // R2 recommends only sending checksums when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    private static void requireSet(String value, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(env + " must be set when STORAGE_TYPE=r2");
        }
    }

    private String bucket(StorageArea area) {
        return area == StorageArea.ORIGINALS ? config.originalsBucket() : config.mediaBucket();
    }

    @Override
    public PresignedUpload presignOriginalUpload(String key, String contentType, long contentLength, Duration ttl) {
        var presigned = presigner.presignPutObject(r -> r
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(config.originalsBucket()).key(key)
                        .contentType(contentType).contentLength(contentLength)));
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("host"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
        return new PresignedUpload(presigned.url().toString(), "PUT", headers, presigned.expiration());
    }

    @Override
    public Optional<Long> size(StorageArea area, String key) {
        try {
            return Optional.of(s3.headObject(r -> r.bucket(bucket(area)).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] read(StorageArea area, String key) {
        return s3.getObjectAsBytes(r -> r.bucket(bucket(area)).key(key)).asByteArray();
    }

    @Override
    public void write(StorageArea area, String key, byte[] data, String contentType) {
        s3.putObject(r -> r.bucket(bucket(area)).key(key).contentType(contentType)
                        // Derivatives never change (new photo = new key), so cache aggressively at the edge.
                        .cacheControl(area == StorageArea.MEDIA ? "public, max-age=31536000, immutable" : null),
                RequestBody.fromBytes(data));
    }

    @Override
    public String publicMediaUrl(String key) {
        return GalleryProperties.stripTrailingSlash(config.mediaPublicBaseUrl()) + "/" + key;
    }

    @Override
    public String presignDownload(StorageArea area, String key, Duration ttl, String downloadFileName) {
        return presigner.presignGetObject(r -> r
                        .signatureDuration(ttl)
                        .getObjectRequest(g -> g.bucket(bucket(area)).key(key)
                                .responseContentDisposition("attachment; filename=\"" + safeFileName(downloadFileName) + "\"")))
                .url().toString();
    }

    @Override
    public void delete(StorageArea area, String key) {
        s3.deleteObject(r -> r.bucket(bucket(area)).key(key));
    }

    @Override
    public void deletePrefix(StorageArea area, String prefix) {
        String bucket = bucket(area);
        var pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
        List<ObjectIdentifier> batch = new ArrayList<>();
        for (S3Object object : pages.contents()) {
            batch.add(ObjectIdentifier.builder().key(object.key()).build());
            if (batch.size() == 1000) {
                deleteBatch(bucket, batch);
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            deleteBatch(bucket, batch);
        }
    }

    private void deleteBatch(String bucket, List<ObjectIdentifier> keys) {
        s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(keys).quiet(true).build()));
    }

    static String safeFileName(String name) {
        return name == null ? "photo.jpg" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @PreDestroy
    void close() {
        s3.close();
        presigner.close();
    }
}
