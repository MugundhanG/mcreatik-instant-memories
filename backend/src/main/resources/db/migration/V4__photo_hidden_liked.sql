-- hidden: stays in the event (and the dashboard) but guests never see it.
-- liked: the photographer's picks, for choosing the best photos; never shown to guests.
ALTER TABLE photos ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE photos ADD COLUMN liked BOOLEAN NOT NULL DEFAULT false;

DROP INDEX ix_photos_feed;
CREATE INDEX ix_photos_feed ON photos (event_id, ready_at DESC, id DESC) WHERE status = 'READY' AND NOT hidden;
