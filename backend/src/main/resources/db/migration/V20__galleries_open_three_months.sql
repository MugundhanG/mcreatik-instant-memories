-- Galleries stay open 3 months (about 90 days) instead of 6: storage is priced per GB per month, so a shorter
-- period keeps a gallery's price low. Galleries created before this keep the date they already have.
ALTER TABLE billing_settings ALTER COLUMN active_months SET DEFAULT 3;
UPDATE billing_settings SET active_months = 3 WHERE active_months = 6;
