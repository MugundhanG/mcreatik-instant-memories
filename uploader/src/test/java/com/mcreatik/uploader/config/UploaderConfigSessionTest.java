package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploaderConfigSessionTest {

    @TempDir
    Path dir;

    private UploaderConfig config(UploaderConfig.Session session) {
        return new UploaderConfig("https://im-api.mcreatik.com", "mku_abc", dir.resolve("p"), 2, "dev",
                UploaderConfig.Ftp.generate(false), session);
    }

    @Test
    void theSignInTokenIsKeptButNeverAPassword() throws Exception {
        Instant expires = Instant.parse("2026-12-01T12:00:00Z");
        config(new UploaderConfig.Session("hello@lenslight.test", "jwt-123", expires)).save(dir);

        String text = Files.readString(dir.resolve(UploaderConfig.FILE_NAME));
        assertThat(text).contains("sessionToken=jwt-123").contains("sessionEmail=hello@lenslight.test");
        // The studio password is not a config key at all (ftpPassword is the camera's own short login).
        assertThat(UploaderConfig.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.equals("password"));
        assertThat(text).doesNotContain("sessionPassword").doesNotContain("\npassword=");

        UploaderConfig back = UploaderConfig.load(dir).orElseThrow();
        assertThat(back.session()).isEqualTo(new UploaderConfig.Session("hello@lenslight.test", "jwt-123", expires));
        assertThat(back.session().valid(Instant.parse("2026-12-01T11:59:00Z"))).isTrue();
        assertThat(back.session().valid(Instant.parse("2026-12-01T12:00:01Z"))).isFalse();
        assertThat(back.toString()).doesNotContain("jwt-123").doesNotContain("mku_abc");
        assertThat(back.session().toString()).doesNotContain("jwt-123");
    }

    @Test
    void aConfigWithoutASessionStaysWithout() throws Exception {
        config(null).save(dir);
        assertThat(Files.readString(dir.resolve(UploaderConfig.FILE_NAME))).doesNotContain("session");
        assertThat(UploaderConfig.load(dir).orElseThrow().session()).isNull();
    }

    @Test
    void changingTheFolderOrFtpKeepsTheSession() {
        var s = new UploaderConfig.Session("a@b.co", "t", Instant.now());
        assertThat(config(s).withWatchFolder(dir).session()).isEqualTo(s);
        assertThat(config(s).withFtp(UploaderConfig.Ftp.generate(true)).session()).isEqualTo(s);
    }
}
