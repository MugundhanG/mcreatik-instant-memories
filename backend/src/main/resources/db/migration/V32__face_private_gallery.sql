-- "Private gallery": with face search on, guests see only the photos they are in (selfie first).
-- face_search_paid: the studio bought the add-on for this event, so it may switch face search on and off itself.
ALTER TABLE events ADD COLUMN face_private boolean NOT NULL DEFAULT false;
ALTER TABLE events ADD COLUMN face_search_paid boolean NOT NULL DEFAULT false;
-- Events with face search on today got it by buying it or from McreatiK: either way the studio may keep switching it.
UPDATE events SET face_search_paid = true WHERE face_search_enabled;
