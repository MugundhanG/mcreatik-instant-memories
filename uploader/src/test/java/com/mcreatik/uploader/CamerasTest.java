package com.mcreatik.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.api.ApiException;
import com.mcreatik.uploader.config.CameraSet;
import com.mcreatik.uploader.config.UploaderConfig;

/** Up to four cameras on one laptop: each gets its own port, folder, queue and uploader token. */
class CamerasTest {

    @TempDir
    Path root;

    @TempDir
    Path photos;

    private static int freeBasePort() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base;
            try (ServerSocket s = new ServerSocket(0)) {
                base = s.getLocalPort();
            }
            boolean allFree = true;
            for (int p = base; p <= base + 8 && allFree; p++) {
                allFree = p < 65535 && CameraSet.portFree(p);
            }
            if (allFree) {
                return base;
            }
        }
        throw new IllegalStateException("no free port range");
    }

    private static void sendOverFtp(int port, String name, byte[] jpeg) throws Exception {
        FTPClient ftp = new FTPClient();
        ftp.connect("127.0.0.1", port);
        assertThat(ftp.login("mcreatik", "pw234567")).isTrue();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();
        assertThat(ftp.storeFile(name, new ByteArrayInputStream(jpeg))).isTrue();
        ftp.disconnect();
    }

    private static void await(String what, BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(30);
        }
    }

    @Test
    void fourCamerasUploadAtTheSameTimeEachAsItself() throws Exception {
        int base = freeBasePort();
        try (FakeMcreatikServer server = new FakeMcreatikServer()) {
            UploaderConfig c1 = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos.resolve("Event"), 2, "dev",
                    new UploaderConfig.Ftp(true, base, "mcreatik", "pw234567"));
            Cameras cameras = Cameras.start(root, c1, false);
            try {
                for (int i = 0; i < 3; i++) {
                    cameras.add();
                }
                assertThat(cameras.all()).extracting(CameraRuntime::number).containsExactly(1, 2, 3, 4);
                assertThat(cameras.all()).extracting(c -> c.config().ftp().port())
                        .containsExactly(base, base + 1, base + 2, base + 3);
                assertThat(cameras.canAdd()).isFalse();
                assertThatThrownBy(cameras::add).isInstanceOf(IllegalStateException.class).hasMessageContaining("4 cameras");

                List<String> shas = new ArrayList<>();
                for (CameraRuntime c : cameras.all()) {
                    byte[] jpeg = TestPhotos.jpeg();
                    shas.add(FakeMcreatikServer.sha256(jpeg));
                    sendOverFtp(c.config().ftp().port(), "IMG_" + c.number() + ".JPG", jpeg);
                }
                await("all four uploaded", () -> server.completedShas().size() == 4
                        && cameras.all().stream().allMatch(c -> c.queue().counts().done() == 1));
                for (int i = 0; i < 4; i++) {
                    CameraRuntime c = cameras.all().get(i);
                    assertThat(server.tokenBySha.get(shas.get(i))).as("camera " + c.number() + " uploads as itself")
                            .isEqualTo(c.config().token());
                    assertThat(c.queue().counts().done()).isEqualTo(1);
                    assertThat(c.config().watchFolder().resolve("IMG_" + c.number() + ".JPG")).exists();
                }
            } finally {
                cameras.closeAll();
            }

            // the app opens again with the same four cameras on the same ports
            Cameras again = Cameras.start(root, c1, false);
            try {
                assertThat(again.all()).extracting(c -> c.config().ftp().port())
                        .containsExactly(base, base + 1, base + 2, base + 3);
            } finally {
                again.closeAll();
            }
        }
    }

    @Test
    void removingACameraTakesItOutOfTheEventAndOffThisLaptop() throws Exception {
        int base = freeBasePort();
        try (FakeMcreatikServer server = new FakeMcreatikServer()) {
            UploaderConfig c1 = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos.resolve("Event"), 2, "dev",
                    new UploaderConfig.Ftp(true, base, "mcreatik", "pw234567"));
            Cameras cameras = Cameras.start(root, c1, false);
            try {
                CameraRuntime second = cameras.add();
                String token = second.config().token();
                assertThat(server.tokens).contains(token);
                assertThat(cameras.remove(second)).isNull();
                assertThat(server.tokens).doesNotContain(token);
                assertThat(CameraSet.numbers(root)).containsExactly(1);
                assertThat(CameraSet.portFree(base + 1)).as("its FTP port is released").isTrue();
                assertThatThrownBy(() -> cameras.remove(cameras.first())).isInstanceOf(IllegalArgumentException.class);
                // the next one reuses number 2 and its port
                assertThat(cameras.add().config().ftp().port()).isEqualTo(base + 1);
            } finally {
                cameras.closeAll();
            }
        }
    }

    @Test
    void changeEventMovesEveryCameraKeepingPortsAndLogin() throws Exception {
        int base = freeBasePort();
        try (FakeMcreatikServer server = new FakeMcreatikServer()) {
            UploaderConfig c1 = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos.resolve("Event A"), 2, "dev",
                    new UploaderConfig.Ftp(true, base, "mcreatik", "pw234567"));
            Cameras cameras = Cameras.start(root, c1, false);
            try {
                CameraRuntime second = cameras.add();
                String oldToken = second.config().token();
                UploaderConfig newC1 = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos.resolve("Event B"), 2,
                        "dev", c1.ftp());
                assertThat(cameras.moveToNewEvent(newC1)).isEmpty();
                UploaderConfig moved = UploaderConfig.load(CameraSet.dirFor(root, 2)).orElseThrow();
                assertThat(moved.token()).isNotEqualTo(oldToken);
                assertThat(moved.ftp().port()).isEqualTo(base + 1);
                assertThat(moved.ftp().password()).isEqualTo("pw234567");
                assertThat(moved.watchFolder()).isEqualTo(photos.resolve("Event B - Camera 2").toAbsolutePath());
            } finally {
                cameras.closeAll();
            }
        }
    }

    @Test
    void anOlderServerWithoutAddCameraIsExplained() throws Exception {
        int base = freeBasePort();
        try (FakeMcreatikServer server = new FakeMcreatikServer()) {
            server.noCamerasEndpoint = true;
            UploaderConfig c1 = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos.resolve("Event"), 2, "dev",
                    new UploaderConfig.Ftp(true, base, "mcreatik", "pw234567"));
            Cameras cameras = Cameras.start(root, c1, false);
            try {
                assertThatThrownBy(cameras::add).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
                assertThat(cameras.all()).hasSize(1);
                assertThat(CameraSet.numbers(root)).containsExactly(1);
            } finally {
                cameras.closeAll();
            }
        }
    }
}
