-- The studio's contact number (WhatsApp / phone), shown to guests on the gallery and printed board so they can
-- book the studio. Optional; NULL until the studio sets it.
ALTER TABLE admin_users ADD COLUMN contact_phone VARCHAR(20);
