package com.mcreatik.uploader.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.FakeMcreatikServer;
import com.mcreatik.uploader.TestPhotos;
import com.mcreatik.uploader.api.ApiClient;
import com.mcreatik.uploader.queue.UploadQueue;
import com.mcreatik.uploader.source.CompositePhotoSource;
import com.mcreatik.uploader.source.FolderPhotoSource;
import com.mcreatik.uploader.source.FtpPhotoSource;

/** Camera → built-in FTP → queue → McreatiK, exactly as wired in UploaderMain. */
class CameraFtpToGalleryTest {

    @TempDir
    Path photos;

    @TempDir
    Path dataDir;

    @Test
    void photoSentByCameraOverFtpIsUploadedOnce() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        try (FakeMcreatikServer server = new FakeMcreatikServer();
             UploadQueue queue = new UploadQueue(dataDir.resolve("queue.db"))) {
            var source = new CompositePhotoSource(List.of(
                    new FolderPhotoSource(photos, Duration.ofMillis(50), Duration.ZERO, Clock.systemUTC()),
                    new FtpPhotoSource(photos, port, "mcreatik", "pw234567", "0")));
            try (UploadEngine engine = new UploadEngine(new ApiClient(server.url(), FakeMcreatikServer.TOKEN), queue,
                    source, 2, "cam", new Backoff(Duration.ofMillis(20), Duration.ofMillis(200)),
                    System::currentTimeMillis, Duration.ofMillis(100))) {
                engine.start();
                byte[] jpeg = TestPhotos.jpeg();
                FTPClient ftp = new FTPClient();
                ftp.connect("127.0.0.1", port);
                assertThat(ftp.login("mcreatik", "pw234567")).isTrue();
                ftp.setFileType(FTP.BINARY_FILE_TYPE);
                ftp.enterLocalPassiveMode();
                assertThat(ftp.storeFile("IMG_0001.JPG", new ByteArrayInputStream(jpeg))).isTrue();
                ftp.disconnect();

                long deadline = System.currentTimeMillis() + 10_000;
                while (queue.counts().done() < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                Thread.sleep(300); // let the folder scanner also see the file: it must not queue it twice
                assertThat(queue.counts().done()).isEqualTo(1);
                assertThat(queue.counts().total()).isEqualTo(1);
                assertThat(server.completedShas()).containsExactly(FakeMcreatikServer.sha256(jpeg));
            }
        }
    }
}
