package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploaderConfigLegacySessionTest {

    @TempDir
    Path dir;

    @Test
    void aConfigSavedBySignInVersionsStillLoadsAndTheSessionIsDroppedOnSave() throws Exception {
        Files.writeString(dir.resolve(UploaderConfig.FILE_NAME), String.join("\n",
                "server=https://im-api.mcreatik.com", "token=mku_abc", "watchFolder=/photos/Event", "concurrency=2",
                "deviceId=dev", "ftpEnabled=true", "ftpPort=2121", "ftpUser=mcreatik", "ftpPassword=pw234567",
                "sessionEmail=hello@lenslight.test", "sessionToken=jwt-123", "sessionExpiresAt=2026-12-01T12:00:00Z", ""));

        UploaderConfig loaded = UploaderConfig.load(dir).orElseThrow();
        assertThat(loaded.token()).isEqualTo("mku_abc");
        assertThat(loaded.watchFolder()).isEqualTo(Path.of("/photos/Event"));
        assertThat(loaded.ftp().enabled()).isTrue();

        loaded.save(dir);
        String text = Files.readString(dir.resolve(UploaderConfig.FILE_NAME));
        assertThat(text).contains("token=mku_abc").doesNotContain("session").doesNotContain("jwt-123");
        assertThat(UploaderConfig.load(dir).orElseThrow().token()).isEqualTo("mku_abc");
    }
}
