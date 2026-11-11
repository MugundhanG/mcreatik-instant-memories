-- Separate Guest and Client galleries per event: guests see compressed photos, the client sees full quality.
-- Existing photos and categories stay in the guest gallery; existing events keep today's behaviour
-- (split_galleries = false: the couple sees every photo). New events are created with split_galleries = true.
ALTER TABLE photos ADD COLUMN gallery VARCHAR(10) NOT NULL DEFAULT 'GUEST';
ALTER TABLE photos ADD CONSTRAINT ck_photos_gallery CHECK (gallery IN ('GUEST', 'CLIENT'));
ALTER TABLE event_categories ADD COLUMN gallery VARCHAR(10) NOT NULL DEFAULT 'GUEST';
ALTER TABLE event_categories ADD CONSTRAINT ck_event_categories_gallery CHECK (gallery IN ('GUEST', 'CLIENT'));
ALTER TABLE events ADD COLUMN split_galleries BOOLEAN NOT NULL DEFAULT false;

-- Client-type events only ever had a client gallery.
UPDATE photos SET gallery = 'CLIENT' WHERE event_id IN (SELECT id FROM events WHERE event_type = 'CLIENT');
UPDATE event_categories SET gallery = 'CLIENT' WHERE event_id IN (SELECT id FROM events WHERE event_type = 'CLIENT');
UPDATE events SET split_galleries = true WHERE event_type = 'CLIENT';

-- The same file may sit in both galleries of an event.
ALTER TABLE photos DROP CONSTRAINT ux_photos_event_checksum;
ALTER TABLE photos ADD CONSTRAINT ux_photos_event_gallery_checksum UNIQUE (event_id, gallery, checksum_sha256);
CREATE INDEX ix_photos_event_gallery_status ON photos (event_id, gallery, status);
CREATE INDEX ix_event_categories_gallery ON event_categories (event_id, gallery, position);
