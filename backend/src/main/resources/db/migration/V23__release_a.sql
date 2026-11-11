-- A Quick event is for up to about 200 guests (was 300).
ALTER TABLE billing_settings ALTER COLUMN upgrade_guests SET DEFAULT 200;
UPDATE billing_settings SET upgrade_guests = 200 WHERE upgrade_guests = 300;

-- A studio on a paid-up Quick pack pays this for a Main event (instead of the full Main price).
ALTER TABLE billing_settings ADD COLUMN quick_pack_main_price INT NOT NULL DEFAULT 1499;

-- Storage bought together with a client gallery's setup: kept on the studio until the gallery is created,
-- and on the payment so a rejected payment takes it back.
ALTER TABLE admin_users ADD COLUMN client_storage_gb INT NOT NULL DEFAULT 0;
ALTER TABLE event_payments ADD COLUMN storage_gb INT NOT NULL DEFAULT 0;
