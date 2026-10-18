-- Photos can be prepared on the photographer's laptop: the uploader sends the web and thumbnail versions
-- straight to media storage and the full-size original stays on the laptop/card.
ALTER TABLE photos ADD COLUMN original_stored BOOLEAN NOT NULL DEFAULT TRUE;
