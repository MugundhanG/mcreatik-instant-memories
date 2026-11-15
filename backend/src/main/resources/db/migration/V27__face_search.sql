-- "Find my photos": optional face search for the guest live gallery (an add-on per event, AWS Rekognition).
-- Off by default. No selfie is ever stored; only the face ids Rekognition returns for the event's photos are.

-- Per event: the add-on is bought (or switched on by McreatiK for a trial).
ALTER TABLE events ADD COLUMN face_search_enabled BOOLEAN NOT NULL DEFAULT false;

-- Indexing queue state on the photos table (the face worker claims rows like the photo worker does).
-- face_indexed_at: set once the photo's faces are in the event's collection (also when it has no faces).
-- face_attempts / face_next_attempt_at: retries with backoff; a photo is given up after the configured attempts.
ALTER TABLE photos ADD COLUMN face_indexed_at TIMESTAMPTZ;
ALTER TABLE photos ADD COLUMN face_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE photos ADD COLUMN face_next_attempt_at TIMESTAMPTZ;
CREATE INDEX ix_photos_face_queue ON photos (ready_at)
    WHERE gallery = 'GUEST' AND status = 'READY' AND face_indexed_at IS NULL;

-- The Rekognition face ids of each photo. Deleting a photo (or an event) removes its rows by cascade;
-- the provider's copies are deleted explicitly by the application.
CREATE TABLE photo_faces (
    photo_id UUID NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    face_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (photo_id, face_id)
);
CREATE INDEX ix_photo_faces_event_face ON photo_faces (event_id, face_id);

-- Searches per event and day (India time): the daily cap that keeps the AWS bill bounded.
CREATE TABLE face_search_usage (
    event_id UUID NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    day      DATE NOT NULL,
    searches INT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, day)
);

-- What the add-on costs (INR), by event type. event_payments.item is a plain VARCHAR(20) without a CHECK,
-- so the new item FACE_SEARCH needs no constraint change.
ALTER TABLE billing_settings ADD COLUMN face_search_main_price INT NOT NULL DEFAULT 999;
ALTER TABLE billing_settings ADD COLUMN face_search_quick_price INT NOT NULL DEFAULT 499;
