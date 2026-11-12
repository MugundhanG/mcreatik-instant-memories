-- Months-based pricing, retention that keeps the event record, and client logins that can reuse an email.

-- Open months bought with a client gallery's setup (handed to the gallery when it is created, like client_storage_gb),
-- and the months each payment bought (EXTEND, CLIENT_SETUP). 0 = not recorded (older payments).
ALTER TABLE admin_users ADD COLUMN client_months INT NOT NULL DEFAULT 1;
ALTER TABLE event_payments ADD COLUMN months INT NOT NULL DEFAULT 0;

-- When a gallery's photos were deleted after its open period + 30 days. The event row, its payments and its
-- charges stay as the business record; the event is ARCHIVED.
ALTER TABLE events ADD COLUMN photos_deleted_at TIMESTAMPTZ;

-- One email may be the client login of several events (a couple booking twice). Still one login per event and email.
ALTER TABLE event_clients DROP CONSTRAINT IF EXISTS event_clients_email_key;
CREATE INDEX ix_event_clients_email ON event_clients (lower(email));
CREATE UNIQUE INDEX ux_event_clients_event_email ON event_clients (event_id, lower(email));
