-- Studio logo watermark on photos guests download and share. On by default; the studio switches it per event.
ALTER TABLE events ADD COLUMN watermark BOOLEAN NOT NULL DEFAULT TRUE;
