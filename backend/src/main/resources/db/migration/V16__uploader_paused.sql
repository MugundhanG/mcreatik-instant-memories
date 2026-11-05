-- "Pause camera" (privacy pause): while paused, the uploader keeps new photos on the laptop and the server refuses its
-- uploads, so nothing from that camera reaches the gallery until it is resumed.
ALTER TABLE uploaders ADD COLUMN paused BOOLEAN NOT NULL DEFAULT false;
