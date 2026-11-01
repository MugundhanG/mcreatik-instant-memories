-- Photo categories per event (Couple, Family & Groups, ...), editable by the studio.
CREATE TABLE event_categories (
    id          UUID PRIMARY KEY,
    event_id    UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name        VARCHAR(60)  NOT NULL,
    position    INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_categories_event ON event_categories (event_id, position);

ALTER TABLE photos ADD COLUMN category_id UUID REFERENCES event_categories (id) ON DELETE SET NULL;
CREATE INDEX ix_photos_category ON photos (event_id, category_id);

-- Existing events get the default list too.
INSERT INTO event_categories (id, event_id, name, position)
SELECT gen_random_uuid(), e.id, c.name, c.pos
FROM events e
CROSS JOIN (VALUES ('Couple', 1), ('Family & Groups', 2), ('Rituals', 3), ('Cake cutting', 4),
                   ('Main event', 5), ('Candid', 6)) AS c (name, pos);

-- The couple's logins: they browse all photos (originals included) and select photos for the album.
CREATE TABLE event_clients (
    id                    UUID PRIMARY KEY,
    event_id              UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name                  VARCHAR(120) NOT NULL,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    must_change_password  BOOLEAN      NOT NULL DEFAULT true,
    disabled              BOOLEAN      NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at         TIMESTAMPTZ
);
CREATE INDEX ix_event_clients_event ON event_clients (event_id);

-- One album per event, shared by its clients (bride and groom see the same selection).
ALTER TABLE events ADD COLUMN album_limit INT;
CREATE TABLE album_selections (
    event_id    UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    photo_id    UUID        NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    client_id   UUID        REFERENCES event_clients (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, photo_id)
);
