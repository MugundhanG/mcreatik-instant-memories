-- McreatiK (super admin) can turn off an event's guest gallery and/or the couple's gallery, e.g. for non-payment.
ALTER TABLE events ADD COLUMN live_gallery_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE events ADD COLUMN client_gallery_enabled BOOLEAN NOT NULL DEFAULT true;

-- "Forgot password": a temporary password sent by email works alongside the current one for 30 minutes, so a
-- stranger who types someone's email cannot lock them out. Only its hash is stored.
ALTER TABLE admin_users ADD COLUMN reset_password_hash VARCHAR(100);
ALTER TABLE admin_users ADD COLUMN reset_expires_at TIMESTAMPTZ;
ALTER TABLE admin_users ADD COLUMN reset_requested_at TIMESTAMPTZ;
ALTER TABLE event_clients ADD COLUMN reset_password_hash VARCHAR(100);
ALTER TABLE event_clients ADD COLUMN reset_expires_at TIMESTAMPTZ;
ALTER TABLE event_clients ADD COLUMN reset_requested_at TIMESTAMPTZ;
