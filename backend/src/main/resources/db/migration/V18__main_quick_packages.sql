-- Packages: Main and Quick events, monthly packs of 2 or 4, typed credits, and typed purchases.

-- Each event is a Main (weddings, receptions, big events) or Quick (up to ~300 guests) event. Existing events are Main.
-- CLIENT (a client gallery without a live event) is used from V19.
ALTER TABLE events ADD COLUMN event_type VARCHAR(10) NOT NULL DEFAULT 'MAIN'
    CHECK (event_type IN ('MAIN', 'QUICK', 'CLIENT'));

-- Plans: PER_EVENT, MAIN_2, MAIN_4, QUICK_2, QUICK_4, UNLIMITED; MONTHLY stays for studios already on it.
-- Bought events are typed: a Main credit covers a Main or a Quick event, a Quick credit only a Quick event.
ALTER TABLE admin_users RENAME COLUMN event_credits TO main_credits;
ALTER TABLE admin_users ADD COLUMN quick_credits INT NOT NULL DEFAULT 0;
-- A monthly pack is paid month by month: its allowance stops a few days after this date unless renewed.
ALTER TABLE admin_users ADD COLUMN plan_paid_until DATE;
-- The day the current plan started (carry-over needs a whole previous month on the same pack).
ALTER TABLE admin_users ADD COLUMN plan_since DATE NOT NULL DEFAULT CURRENT_DATE;
-- The renewal reminder already emailed for this paid-until date (one email per period).
ALTER TABLE admin_users ADD COLUMN plan_reminded_for DATE;

-- Prices. per_event_price is the single Main event price; extra_event_price and monthly_price stay for MONTHLY.
ALTER TABLE billing_settings
    ADD COLUMN quick_price    INT NOT NULL DEFAULT 1499,
    ADD COLUMN main2_price    INT NOT NULL DEFAULT 3499,
    ADD COLUMN main4_price    INT NOT NULL DEFAULT 4999,
    ADD COLUMN quick2_price   INT NOT NULL DEFAULT 2499,
    ADD COLUMN quick4_price   INT NOT NULL DEFAULT 3999,
    ADD COLUMN upgrade_price  INT NOT NULL DEFAULT 1000,
    ADD COLUMN upgrade_guests INT NOT NULL DEFAULT 300;
UPDATE billing_settings SET per_event_price = 2499 WHERE per_event_price = 2500;

-- What a payment buys: MAIN_EVENT / QUICK_EVENT (events = how many), UPGRADE (a Quick event to Main),
-- PLAN (one month of a monthly pack, plan = which). STORAGE / CLIENT_SETUP / EXTEND come with V19.
ALTER TABLE event_payments ADD COLUMN item VARCHAR(20) NOT NULL DEFAULT 'MAIN_EVENT';
ALTER TABLE event_payments ADD COLUMN event_id UUID REFERENCES events (id) ON DELETE SET NULL;
ALTER TABLE event_payments ADD COLUMN plan VARCHAR(20);

-- How an event was covered: FREE, QUOTA (this month's pack), CARRY (last month's unused), CREDIT (bought).
ALTER TABLE event_charges ALTER COLUMN kind TYPE VARCHAR(12);
