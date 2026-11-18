-- Studios can switch their own guest and client galleries on and off. When McreatiK (super admin) switches one off
-- (e.g. unpaid), the studio cannot switch it back on itself: these flags remember who turned it off.
ALTER TABLE events ADD COLUMN live_gallery_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN client_gallery_locked BOOLEAN NOT NULL DEFAULT FALSE;
-- Galleries that are off today were turned off by McreatiK (or by the unpaid-storage job): keep them off.
UPDATE events SET live_gallery_locked = TRUE WHERE NOT live_gallery_enabled;
UPDATE events SET client_gallery_locked = TRUE WHERE NOT client_gallery_enabled;
