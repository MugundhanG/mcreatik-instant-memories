-- Studio plans: MONTHLY (a monthly allowance of events, then paid extras), PER_EVENT (every event paid),
-- UNLIMITED (no limits; existing logins keep working exactly as before).
ALTER TABLE admin_users
    ADD COLUMN plan           VARCHAR(20) NOT NULL DEFAULT 'PER_EVENT',
    ADD COLUMN monthly_quota  INT         NOT NULL DEFAULT 4,
    ADD COLUMN event_credits  INT         NOT NULL DEFAULT 0;
UPDATE admin_users SET plan = 'UNLIMITED';

-- Every event a studio creates, and how it was covered. Kept when the event is deleted, so deleting an event
-- never gives back the monthly allowance.
CREATE TABLE event_charges (
    id          UUID PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES admin_users (id) ON DELETE CASCADE,
    event_id    UUID        REFERENCES events (id) ON DELETE SET NULL,
    kind        VARCHAR(10) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_charges_owner ON event_charges (owner_id, kind, created_at);

-- UPI payments for extra events: credited at once, checked by McreatiK against the bank statement.
CREATE TABLE event_payments (
    id           UUID PRIMARY KEY,
    studio_id    UUID         NOT NULL REFERENCES admin_users (id) ON DELETE CASCADE,
    events       INT          NOT NULL,
    unit_price   INT          NOT NULL,
    amount       INT          NOT NULL,
    utr          VARCHAR(40)  NOT NULL UNIQUE,
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    note         VARCHAR(300),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at  TIMESTAMPTZ
);
CREATE INDEX ix_event_payments_status ON event_payments (status, created_at);

CREATE TABLE billing_settings (
    id                 INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    upi_id             VARCHAR(100),
    payee_name         VARCHAR(100),
    per_event_price    INT NOT NULL DEFAULT 2500,
    extra_event_price  INT NOT NULL DEFAULT 999,
    monthly_price      INT NOT NULL DEFAULT 5000
);
INSERT INTO billing_settings (id) VALUES (1);
