package com.mcreatik.uploader.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.engine.EngineStatus.Connection;
import com.mcreatik.uploader.queue.QueueCounts;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.ui.LiveState.Conn;
import com.mcreatik.uploader.ui.LiveState.Tone;

class LiveStateTest {

    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    private EngineStatus engine(Connection c, QueueCounts counts, Instant onlineSince, Instant offlineSince, long speed,
                                List<EngineStatus.ActiveUpload> active) {
        return new EngineStatus(c, c == Connection.BLOCKED ? "This uploader was removed from the event." : null,
                "Kavin & Nila Wedding", "Camera 1", "https://live.mcreatik.com/e/x", "folder", counts, active, null,
                onlineSince, offlineSince, speed, null, null, "LIVE");
    }

    private FtpPhotoSource.Status ftp(int received, Instant last, String error) {
        return new FtpPhotoSource.Status(error == null, 2121, "mcreatik", "pw", List.of("192.168.43.10"), received, last,
                received > 0 ? "192.168.43.20" : null, error);
    }

    private static final QueueCounts NONE = new QueueCounts(0, 0, 0, 0, 0);

    @Test
    void nothingIsGreenBeforeARealPhotoOrConnection() {
        LiveState s = LiveState.of(engine(Connection.CONNECTING, NONE, null, null, 0, List.of()), ftp(0, null, null), null, now);
        assertThat(s.conn()).isEqualTo(Conn.RECONNECTING);
        assertThat(s.pipeline()).extracting(LiveState.Stage::tone).doesNotContain(Tone.GREEN);
        assertThat(s.camera()).isEqualTo("No photo yet");
        assertThat(s.lastPhoto()).isEqualTo("None yet");
        assertThat(s.speed()).isEqualTo("—");
        assertThat(s.total()).isZero();
    }

    @Test
    void onlineAndIdleWithPhotosIsGreenAllTheWay() {
        Instant last = now.minusSeconds(90);
        LiveState s = LiveState.of(engine(Connection.ONLINE, new QueueCounts(0, 0, 40, 2, 0), now.minusSeconds(3720), null, 0,
                List.of()), ftp(42, last, null), last, now);
        assertThat(s.conn()).isEqualTo(Conn.ONLINE);
        assertThat(s.tone()).isEqualTo(Tone.GREEN);
        assertThat(s.pipeline()).extracting(LiveState.Stage::tone).containsOnly(Tone.GREEN);
        assertThat(s.duration()).isEqualTo("Online for 1 h 2 min");
        assertThat(s.lastPhoto()).isEqualTo("1 min ago");
        assertThat(s.uploaded()).isEqualTo(42);
        assertThat(s.total()).isEqualTo(42);
        assertThat(s.autoUpload()).isEqualTo("On · up to date");
    }

    @Test
    void uploadingShowsBlueStagesAndTheRealSpeed() {
        LiveState s = LiveState.of(engine(Connection.ONLINE, new QueueCounts(3, 2, 10, 0, 0), now.minusSeconds(60), null,
                1_400_000, List.of(new EngineStatus.ActiveUpload("A.JPG", 10, 100))), ftp(15, now, null), now, now);
        assertThat(s.pipeline().get(1).tone()).isEqualTo(Tone.BLUE);
        assertThat(s.pipeline().get(1).detail()).isEqualTo("5 photos to upload");
        assertThat(s.pipeline().get(2).tone()).isEqualTo(Tone.BLUE);
        assertThat(s.speed()).isEqualTo("1.4 MB/s");
        assertThat(s.autoUpload()).isEqualTo("Uploading 5 photos");
    }

    @Test
    void aDroppedConnectionIsReconnectingThenOffline() {
        QueueCounts waiting = new QueueCounts(4, 0, 10, 0, 0);
        LiveState soon = LiveState.of(engine(Connection.OFFLINE, waiting, null, now.minusSeconds(30), 0, List.of()),
                ftp(14, now, null), now, now);
        assertThat(soon.conn()).isEqualTo(Conn.RECONNECTING);
        assertThat(soon.pipeline().get(2).tone()).isEqualTo(Tone.RED);
        assertThat(soon.autoUpload()).isEqualTo("Paused · 4 photos waiting");

        LiveState later = LiveState.of(engine(Connection.OFFLINE, waiting, null, now.minusSeconds(180), 0, List.of()),
                ftp(14, now, null), now, now);
        assertThat(later.conn()).isEqualTo(Conn.OFFLINE);
        assertThat(later.tone()).isEqualTo(Tone.AMBER);
        assertThat(later.duration()).isEqualTo("Offline for 3 min");
    }

    @Test
    void aRemovedUploaderOrABrokenCameraReceiverIsAnError() {
        LiveState blocked = LiveState.of(engine(Connection.BLOCKED, NONE, null, null, 0, List.of()), ftp(0, null, null), null, now);
        assertThat(blocked.conn()).isEqualTo(Conn.ERROR);
        assertThat(blocked.detail()).contains("removed");
        assertThat(blocked.pipeline().get(2).tone()).isEqualTo(Tone.RED);

        LiveState port = LiveState.of(engine(Connection.ONLINE, NONE, now, null, 0, List.of()),
                ftp(0, null, "Could not start on port 2121"), null, now);
        assertThat(port.conn()).isEqualTo(Conn.ERROR);
        assertThat(port.detail()).contains("2121");
        assertThat(port.pipeline().get(0).tone()).isEqualTo(Tone.RED);
    }

    @Test
    void failedPhotosShowOnTheServerStage() {
        LiveState s = LiveState.of(engine(Connection.ONLINE, new QueueCounts(0, 0, 5, 0, 2), now, null, 0, List.of()),
                ftp(7, now, null), now, now);
        assertThat(s.pipeline().get(2).tone()).isEqualTo(Tone.RED);
        assertThat(s.pipeline().get(2).status()).isEqualTo("2 failed photos");
        assertThat(s.uploaded()).isEqualTo(5);
        assertThat(s.total()).isEqualTo(7);
    }

    @Test
    void folderModeWatchesTheFolder() {
        LiveState s = LiveState.of(engine(Connection.ONLINE, NONE, now, null, 0, List.of()), null, null, now);
        assertThat(s.camera()).isEqualTo("Watching the folder");
        assertThat(s.pipeline().get(0).name()).isEqualTo("Camera folder");
        assertThat(s.pipeline().get(0).tone()).isEqualTo(Tone.GREY);
    }

    @Test
    void cameraNamesAreReadable() {
        assertThat(PhotoInfo.cameraName("Canon", "Canon EOS R6m2")).isEqualTo("Canon EOS R6 Mark II");
        assertThat(PhotoInfo.cameraName("SONY", "ILCE-7M4")).isEqualTo("Sony α7 IV");
        assertThat(PhotoInfo.cameraName("NIKON CORPORATION", "NIKON Z 6_2")).isEqualTo("NIKON Z 6_2");
        assertThat(PhotoInfo.cameraName("FUJIFILM", "X-T5")).isEqualTo("Fujifilm X-T5");
        assertThat(PhotoInfo.cameraName(null, null)).isNull();
    }
}
