-- A guest's request for the full-quality version of a photo, with how to reach them (email or WhatsApp number):
-- a lead for the studio. Unlike guest_activity this holds contact details the guest typed in themselves.
CREATE TABLE hq_requests (
    id          UUID PRIMARY KEY,
    event_id    UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    photo_id    UUID         REFERENCES photos (id) ON DELETE SET NULL,
    name        VARCHAR(80),
    contact     VARCHAR(320) NOT NULL,
    kind        VARCHAR(10)  NOT NULL, -- EMAIL or WHATSAPP
    visitor_id  VARCHAR(40),
    done        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_hq_requests_event ON hq_requests (event_id, created_at DESC);

-- A guest likes a photo once: finding their like (to skip a repeat, or take it back) must be quick.
CREATE INDEX ix_guest_activity_like ON guest_activity (event_id, photo_id, visitor_id) WHERE type = 'LIKE';
