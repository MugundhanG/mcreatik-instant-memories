-- How big the studio's logo is printed on its QR posters, in percent of the standard size.
ALTER TABLE admin_users ADD COLUMN logo_scale INT NOT NULL DEFAULT 100;
