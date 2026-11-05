-- Client galleries (a couple's gallery without a live event), paid storage and a 6-month active period.

-- Storage an event may use, in GB (web versions + full-quality originals). Live events get the free allowance;
-- a client gallery starts at 0 and the studio buys storage for it.
ALTER TABLE events ADD COLUMN storage_quota_gb INT NOT NULL DEFAULT 5;
-- Galleries are open until this day; the files are deleted 30 days later (retention_until) unless extended.
-- Existing events keep exactly what they had: open until their delete date, or for good when they had none.
ALTER TABLE events ADD COLUMN active_until DATE;
UPDATE events SET active_until = retention_until;
-- The couple's gallery was turned off because a live event went over its storage and none was bought in time;
-- buying storage turns it back on (McreatiK's own switch, client_gallery_enabled, is the same column).
ALTER TABLE events ADD COLUMN storage_locked BOOLEAN NOT NULL DEFAULT false;
-- Which expiry emails were sent for the current active_until (one of each per period).
ALTER TABLE events ADD COLUMN expiry_warned_for DATE;
ALTER TABLE events ADD COLUMN expiry_notified_for DATE;

-- Client galleries paid for (₹250 each): one is used each time a client gallery is created.
ALTER TABLE admin_users ADD COLUMN client_credits INT NOT NULL DEFAULT 0;

ALTER TABLE billing_settings
    ADD COLUMN storage_gb_price   INT NOT NULL DEFAULT 15,
    ADD COLUMN free_storage_gb    INT NOT NULL DEFAULT 5,
    ADD COLUMN client_setup_price INT NOT NULL DEFAULT 250,
    ADD COLUMN active_months      INT NOT NULL DEFAULT 6;
-- event_payments.item gains STORAGE (events = GB, event_id), CLIENT_SETUP (events = galleries) and
-- EXTEND (events = GB charged, event_id): no schema change needed.
