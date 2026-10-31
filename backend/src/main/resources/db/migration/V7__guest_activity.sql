-- Anonymous guest activity on public galleries, for the studio's analytics. No personal data:
-- visitor_id is a random id the guest's browser makes up and keeps; it only counts distinct people.
CREATE TABLE guest_activity (
    id          BIGSERIAL PRIMARY KEY,
    event_id    UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    type        VARCHAR(20) NOT NULL,
    visitor_id  VARCHAR(40) NOT NULL,
    photo_id    UUID,
    source      VARCHAR(10),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_guest_activity_event ON guest_activity (event_id, type, created_at);
CREATE INDEX ix_guest_activity_photo ON guest_activity (event_id, photo_id) WHERE photo_id IS NOT NULL;
