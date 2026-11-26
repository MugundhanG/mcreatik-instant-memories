-- How the studio's mark looks on guests' downloads and shares (the per-event on/off switch is events.watermark).
-- wm_style: CORNER (text/logo in a corner of the photo) or STRIP (a band below the photo).
ALTER TABLE admin_users
    ADD COLUMN wm_style     VARCHAR(10) NOT NULL DEFAULT 'CORNER',
    ADD COLUMN wm_position  VARCHAR(10) NOT NULL DEFAULT 'RIGHT',
    ADD COLUMN wm_size      VARCHAR(10) NOT NULL DEFAULT 'SMALL',
    ADD COLUMN wm_logo      BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN wm_name      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN wm_instagram BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN wm_phone     BOOLEAN     NOT NULL DEFAULT FALSE;
