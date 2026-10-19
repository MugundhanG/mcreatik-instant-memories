-- Bytes of the web + thumbnail versions in media storage, so the dashboard can report real cloud storage
-- (file_size is the camera original, which is not uploaded for uploader-prepared photos).
ALTER TABLE photos ADD COLUMN media_bytes BIGINT NOT NULL DEFAULT 0;
