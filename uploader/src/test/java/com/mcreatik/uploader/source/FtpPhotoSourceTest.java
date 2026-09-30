package com.mcreatik.uploader.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.TestPhotos;

class FtpPhotoSourceTest {

    private static final String USER = "mcreatik";
    private static final String PASSWORD = "k7m2p9xq";

    @TempDir
    Path photos;

    int port;
    FtpPhotoSource source;
    final List<Path> emitted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        port = freePort();
        source = new FtpPhotoSource(photos, port, USER, PASSWORD, "0");
        source.start(emitted::add);
        assertThat(source.status().error()).isNull();
        assertThat(source.status().running()).isTrue();
    }

    @AfterEach
    void stop() {
        source.close();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private FTPClient login() throws Exception {
        FTPClient ftp = new FTPClient();
        ftp.connect("127.0.0.1", port);
        assertThat(ftp.login(USER, PASSWORD)).isTrue();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void cameraUploadInPassiveModeLandsInPhotoFolderAndIsHandedOverImmediately() throws Exception {
        byte[] jpeg = TestPhotos.jpeg();
        FTPClient ftp = login();
        ftp.enterLocalPassiveMode();
        assertThat(ftp.storeFile("IMG_0001.JPG", new ByteArrayInputStream(jpeg))).isTrue();
        ftp.logout();
        ftp.disconnect();

        Path published = photos.resolve("IMG_0001.JPG");
        await(() -> !emitted.isEmpty());
        assertThat(emitted).containsExactly(published);
        assertThat(Files.readAllBytes(published)).isEqualTo(jpeg);
        assertThat(photos.resolve(FtpPhotoSource.INCOMING_DIR).resolve("IMG_0001.JPG")).doesNotExist();
        assertThat(source.status().filesReceived()).isEqualTo(1);
        assertThat(source.status().lastClient()).isEqualTo("127.0.0.1");
    }

    @Test
    void activeModeWorksToo() throws Exception {
        FTPClient ftp = login();
        ftp.enterLocalActiveMode();
        assertThat(ftp.storeFile("IMG_0002.JPG", new ByteArrayInputStream(TestPhotos.jpeg()))).isTrue();
        ftp.disconnect();
        await(() -> !emitted.isEmpty());
        assertThat(photos.resolve("IMG_0002.JPG")).exists();
    }

    @Test
    void keepsTheCamerasFolderStructure() throws Exception {
        FTPClient ftp = login();
        ftp.enterLocalPassiveMode();
        assertThat(ftp.makeDirectory("A")).isTrue();
        assertThat(ftp.makeDirectory("A/DCIM")).isTrue();
        assertThat(ftp.makeDirectory("A/DCIM/100EOSR6")).isTrue();
        assertThat(ftp.changeWorkingDirectory("A/DCIM/100EOSR6")).isTrue();
        assertThat(ftp.storeFile("IMG_0003.JPG", new ByteArrayInputStream(TestPhotos.jpeg()))).isTrue();
        ftp.disconnect();
        await(() -> !emitted.isEmpty());
        assertThat(emitted).containsExactly(photos.resolve("A/DCIM/100EOSR6/IMG_0003.JPG"));
    }

    @Test
    void rejectsWrongPasswordAndAnonymousLogin() throws Exception {
        FTPClient ftp = new FTPClient();
        ftp.connect("127.0.0.1", port);
        assertThat(ftp.login(USER, "wrong")).isFalse();
        ftp.disconnect();
        ftp.connect("127.0.0.1", port);
        assertThat(ftp.login("anonymous", "guest@example.com")).isFalse();
        ftp.disconnect();
    }

    @Test
    void identicalResendIsDroppedAndDifferentPhotoWithSameNameIsKept() throws Exception {
        byte[] first = TestPhotos.jpeg();
        byte[] other = TestPhotos.jpeg();
        FTPClient ftp = login();
        ftp.enterLocalPassiveMode();
        ftp.storeFile("IMG_0004.JPG", new ByteArrayInputStream(first));
        ftp.storeFile("IMG_0004.JPG", new ByteArrayInputStream(first)); // camera re-sends after reconnect
        ftp.storeFile("IMG_0004.JPG", new ByteArrayInputStream(other)); // new card, numbering restarted
        ftp.disconnect();

        await(() -> emitted.size() >= 2);
        assertThat(emitted).containsExactly(photos.resolve("IMG_0004.JPG"), photos.resolve("IMG_0004_1.JPG"));
        assertThat(Files.readAllBytes(photos.resolve("IMG_0004.JPG"))).isEqualTo(first);
        assertThat(Files.readAllBytes(photos.resolve("IMG_0004_1.JPG"))).isEqualTo(other);
    }

    /** Wi-Fi drops mid-file: the connection is reset, the half photo must never be published. */
    @Test
    void interruptedTransferIsNeverPublished() throws Exception {
        try (Socket control = new Socket("127.0.0.1", port)) {
            var in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = control.getOutputStream();
            readReply(in);
            command(out, in, "USER " + USER);
            command(out, in, "PASS " + PASSWORD);
            command(out, in, "TYPE I");
            String pasv = command(out, in, "PASV");
            Matcher m = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").matcher(pasv);
            assertThat(m.find()).isTrue();
            int dataPort = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            Socket data = new Socket("127.0.0.1", dataPort);
            command(out, in, "STOR IMG_0005.JPG");
            byte[] jpeg = TestPhotos.jpeg();
            data.getOutputStream().write(jpeg, 0, jpeg.length / 2);
            data.getOutputStream().flush();
            data.setSoLinger(true, 0); // RST instead of a clean close, like a dead Wi-Fi link
            data.close();
            control.setSoLinger(true, 0);
        }
        Thread.sleep(500);
        assertThat(emitted).isEmpty();
        assertThat(photos.resolve("IMG_0005.JPG")).doesNotExist();
    }

    @Test
    void portAlreadyInUseIsReportedNotThrown() throws Exception {
        FtpPhotoSource clash = new FtpPhotoSource(photos, port, USER, PASSWORD, "0");
        clash.start(emitted::add);
        assertThat(clash.status().running()).isFalse();
        assertThat(clash.status().error()).contains("could not start on port " + port);
        clash.close();
    }

    @Test
    void incomingFolderIsHiddenFromTheFolderScanner() throws Exception {
        Path incomingFile = photos.resolve(FtpPhotoSource.INCOMING_DIR).resolve("partial.jpg");
        TestPhotos.write(incomingFile.getParent(), "partial.jpg");
        FolderPhotoSource scanner = new FolderPhotoSource(photos, java.time.Duration.ofMillis(10),
                java.time.Duration.ZERO, java.time.Clock.systemUTC());
        scanner.scanOnce();
        assertThat(scanner.scanOnce()).isEmpty();
    }

    private static String command(OutputStream out, BufferedReader in, String cmd) throws Exception {
        out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readReply(in);
    }

    private static String readReply(BufferedReader in) throws Exception {
        String line = in.readLine();
        while (line != null && line.length() >= 4 && line.charAt(3) == '-') {
            line = in.readLine();
        }
        return line;
    }
}
