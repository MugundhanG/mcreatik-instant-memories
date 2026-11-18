-- A UPI payment from a studio with no confirmed payment yet is held: what it bought is added only when McreatiK
-- approves it. Existing payments were all added at once.
ALTER TABLE event_payments ADD COLUMN applied boolean NOT NULL DEFAULT true;
