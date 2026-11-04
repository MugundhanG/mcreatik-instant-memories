package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CameraSetTest {

    @Test
    void camera1IsTheDataFolderAndOthersHaveTheirOwn(@TempDir Path root) throws Exception {
        assertThat(CameraSet.dirFor(root, 1)).isEqualTo(root);
        assertThat(CameraSet.dirFor(root, 3)).isEqualTo(root.resolve("cameras").resolve("3"));
        assertThat(CameraSet.numbers(root)).containsExactly(1);
        UploaderConfig c = new UploaderConfig("http://x", "t", root.resolve("p"), 2, "d", UploaderConfig.Ftp.generate(true));
        c.save(CameraSet.dirFor(root, 3));
        assertThat(CameraSet.numbers(root)).containsExactly(1, 3);
        CameraSet.deleteDir(root, 3);
        assertThat(CameraSet.numbers(root)).containsExactly(1);
        assertThatThrownBy(() -> CameraSet.deleteDir(root, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numbersFillGapsAndStopAtFour() {
        assertThat(CameraSet.nextNumber(List.of(1))).isEqualTo(2);
        assertThat(CameraSet.nextNumber(List.of(1, 2, 4))).isEqualTo(3);
        assertThat(CameraSet.nextNumber(List.of(1, 2, 3, 4))).isEqualTo(-1);
    }

    @Test
    void eachCameraGetsTheNextFreePort() {
        assertThat(CameraSet.portFor(2121, List.of(2121), p -> true)).isEqualTo(2122);
        assertThat(CameraSet.portFor(2121, List.of(2121, 2122), p -> true)).isEqualTo(2123);
        // a port another program uses is skipped
        assertThat(CameraSet.portFor(2121, List.of(2121), p -> p != 2122)).isEqualTo(2123);
        assertThatThrownBy(() -> CameraSet.portFor(2121, Set.of(2121), p -> false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void folderSitsBesideCamera1sNeverInsideIt() {
        Path c1 = Path.of("/home/a/McreatiK Photos/Ananya & Rohit Wedding");
        Path c2 = CameraSet.folderFor(c1, 2);
        assertThat(c2).isEqualTo(Path.of("/home/a/McreatiK Photos/Ananya & Rohit Wedding - Camera 2"));
        assertThat(c2.startsWith(c1)).as("Camera 1's watcher looks into subfolders").isFalse();
        assertThat(CameraSet.folderFor(c1, 1)).isEqualTo(c1);
    }

    @Test
    void aNewCameraSharesCamera1sLoginButNotItsTokenPortOrFolder() {
        UploaderConfig c1 = new UploaderConfig("http://x", "tok-1", Path.of("/p/Event"), 2, "dev",
                new UploaderConfig.Ftp(true, 2121, "mcreatik", "abcd2345"));
        UploaderConfig c2 = CameraSet.newCamera(c1, "tok-2", 2, 2122);
        assertThat(c2.token()).isEqualTo("tok-2");
        assertThat(c2.ftp().port()).isEqualTo(2122);
        assertThat(c2.ftp().username()).isEqualTo("mcreatik");
        assertThat(c2.ftp().password()).isEqualTo("abcd2345");
        assertThat(c2.watchFolder()).isEqualTo(Path.of("/p/Event - Camera 2"));
        assertThat(c2.deviceId()).isEqualTo("dev");
    }
}
