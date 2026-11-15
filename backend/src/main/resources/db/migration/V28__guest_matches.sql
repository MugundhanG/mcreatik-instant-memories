-- "Find my photos", remember me: the guest's phone keeps one random match id, and this row holds the faces that matched
-- their selfie, so reopening the gallery shows their photos again without another paid search. The selfie itself is
-- never stored. A row goes when the guest taps "Forget me", when the add-on is switched off, and with the event's photos.
CREATE TABLE guest_matches (
    id            UUID PRIMARY KEY,
    event_id      UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    -- The best matches to the selfie (at most 5): "Update my photos" searches by these, not by the selfie.
    seed_face_ids TEXT[]      NOT NULL DEFAULT '{}',
    -- Every face matched so far.
    face_ids      TEXT[]      NOT NULL DEFAULT '{}',
    -- Photos indexed after this are "new" for the guest.
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_guest_matches_event ON guest_matches (event_id);

-- Studio stats for face search live in guest_activity too (types FACE_SEARCH, FACE_FAILED, FACE_FOUND, FACE_UPDATE,
-- written by the server; downloads and likes from face results are the usual types with source 'face').
-- amount: photos found (FACE_FOUND) or photos added by an update (FACE_UPDATE).
ALTER TABLE guest_activity ADD COLUMN amount INT;
