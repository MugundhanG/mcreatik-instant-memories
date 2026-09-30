-- McreatiK Live Gallery V1 schema.
-- Deleting an event cascades to its uploaders and photos; storage objects live under events/{eventId}/.

CREATE TABLE admin_users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_admin_users_email ON admin_users (lower(email));

CREATE TABLE events (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES admin_users (id),
    name            VARCHAR(200) NOT NULL,
    slug            VARCHAR(80)  NOT NULL UNIQUE,
    event_date      DATE         NOT NULL,
    start_time      TIMESTAMPTZ,
    end_time        TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL
        CHECK (status IN ('DRAFT', 'UPCOMING', 'LIVE', 'COMPLETED', 'ARCHIVED')),
    cover_photo_id  UUID,
    retention_until DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_events_owner ON events (owner_id, event_date DESC);

CREATE TABLE uploaders (
    id                UUID PRIMARY KEY,
    event_id          UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name              VARCHAR(120) NOT NULL,
    device_identifier VARCHAR(200),
    token_hash        VARCHAR(64)  NOT NULL UNIQUE,
    token_prefix      VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ONLINE', 'OFFLINE', 'ERROR')),
    last_seen_at      TIMESTAMPTZ,
    last_error        VARCHAR(500),
    queue_pending     INTEGER      NOT NULL DEFAULT 0,
    queue_failed      INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_uploaders_event ON uploaders (event_id);

CREATE TABLE photos (
    id                     UUID PRIMARY KEY,
    event_id               UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    uploader_id            UUID         REFERENCES uploaders (id) ON DELETE SET NULL,
    original_file_name     VARCHAR(255) NOT NULL,
    storage_key            VARCHAR(300) NOT NULL,
    optimized_storage_key  VARCHAR(300),
    thumbnail_storage_key  VARCHAR(300),
    file_size              BIGINT       NOT NULL,
    width                  INTEGER,
    height                 INTEGER,
    mime_type              VARCHAR(50)  NOT NULL,
    checksum_sha256        VARCHAR(64)     NOT NULL,
    status                 VARCHAR(16)  NOT NULL
        CHECK (status IN ('QUEUED', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    failure_reason         VARCHAR(500),
    processing_attempts    INTEGER      NOT NULL DEFAULT 0,
    processing_started_at  TIMESTAMPTZ,
    captured_at            TIMESTAMPTZ,
    uploaded_at            TIMESTAMPTZ,
    processed_at           TIMESTAMPTZ,
    ready_at               TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_photos_event_checksum UNIQUE (event_id, checksum_sha256)
);
-- Guest gallery feed: newest first, cursor = (ready_at, id)
CREATE INDEX ix_photos_feed ON photos (event_id, ready_at DESC, id DESC) WHERE status = 'READY';
-- Processing worker queue
CREATE INDEX ix_photos_work ON photos (status, uploaded_at) WHERE status IN ('UPLOADED', 'PROCESSING');
-- Per-camera statistics
CREATE INDEX ix_photos_uploader ON photos (event_id, uploader_id, status);

ALTER TABLE events
    ADD CONSTRAINT fk_events_cover_photo FOREIGN KEY (cover_photo_id) REFERENCES photos (id) ON DELETE SET NULL;
