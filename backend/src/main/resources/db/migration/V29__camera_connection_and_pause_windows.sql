-- What the uploader reports about the CAMERA itself (its FTP session), separately from the laptop's heartbeat.
-- camera_connected is NULL for uploaders that predate this report (the dashboard then shows "unknown", never "Online").
ALTER TABLE uploaders ADD COLUMN camera_connected BOOLEAN;
ALTER TABLE uploaders ADD COLUMN camera_last_photo_at TIMESTAMPTZ;

-- Privacy pause history. A photo's fate is decided by when it ARRIVED on the laptop: one that arrived inside a pause
-- window is never accepted into the gallery, even when the laptop uploads it later (offline, queued, or after Resume).
CREATE TABLE uploader_pause_windows (
    id          UUID PRIMARY KEY,
    uploader_id UUID        NOT NULL REFERENCES uploaders (id) ON DELETE CASCADE,
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ
);
CREATE INDEX ix_pause_windows_uploader ON uploader_pause_windows (uploader_id, started_at);
