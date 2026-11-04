package com.mcreatik.uploader.ui;

import java.time.Instant;
import java.util.List;

import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.source.FtpPhotoSource;

/** One read of the uploader's state, taken every half second and shared by every page. */
public record Snapshot(EngineStatus engine, FtpPhotoSource.Status ftp, LiveState live, List<QueueItem> recent,
                       Instant now) {
}
