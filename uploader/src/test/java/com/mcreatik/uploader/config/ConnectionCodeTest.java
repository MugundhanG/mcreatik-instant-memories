package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionCodeTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsServerAndToken() {
        String code = ConnectionCode.encode("https://api.mcreatik.com", "mku_abc123");
        ConnectionCode parsed = ConnectionCode.parse("  " + code + "\n");
        assertThat(parsed.server()).isEqualTo("https://api.mcreatik.com");
        assertThat(parsed.token()).isEqualTo("mku_abc123");
    }

    @Test
    void rejectsGarbageWithAHelpfulMessage() {
        assertThatThrownBy(() -> ConnectionCode.parse("hello")).hasMessageContaining("MCK1.");
        assertThatThrownBy(() -> ConnectionCode.parse("MCK1.!!!notbase64")).hasMessageContaining("damaged");
        assertThatThrownBy(() -> ConnectionCode.parse(ConnectionCode.encode("ftp://x", "nope")))
                .hasMessageContaining("incomplete");
    }

    @Test
    void configIsSavedAndLoadedWithoutLeakingTokenInToString() throws Exception {
        UploaderConfig config = new UploaderConfig("https://api.mcreatik.com/", "mku_secret", Path.of("/photos"), 3, "dev-1",
                new UploaderConfig.Ftp(true, 2121, "mcreatik", "k7m2p9xq"));
        config.save(dir);
        UploaderConfig loaded = UploaderConfig.load(dir).orElseThrow();
        assertThat(loaded.serverUrl()).isEqualTo("https://api.mcreatik.com");
        assertThat(loaded.token()).isEqualTo("mku_secret");
        assertThat(loaded.concurrency()).isEqualTo(3);
        assertThat(loaded.deviceId()).isEqualTo("dev-1");
        assertThat(loaded.ftp()).isEqualTo(new UploaderConfig.Ftp(true, 2121, "mcreatik", "k7m2p9xq"));
        assertThat(loaded.toString()).doesNotContain("mku_secret").doesNotContain("k7m2p9xq");
    }

    @Test
    void olderConfigWithoutFtpGetsFtpOffWithGeneratedCredentials() throws Exception {
        java.nio.file.Files.writeString(dir.resolve(UploaderConfig.FILE_NAME),
                "server=https://api.mcreatik.com\ntoken=mku_x\nwatchFolder=/photos\n");
        UploaderConfig loaded = UploaderConfig.load(dir).orElseThrow();
        assertThat(loaded.ftp().enabled()).isFalse();
        assertThat(loaded.ftp().port()).isEqualTo(2121);
        assertThat(loaded.ftp().password()).hasSize(8).matches("[a-z2-9]+");
    }
}
