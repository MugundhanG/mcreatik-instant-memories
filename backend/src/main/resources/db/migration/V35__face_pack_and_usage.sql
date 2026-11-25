-- Find my photos: ₹499 per Main event and ₹299 per Quick event bought one by one, and a monthly add-on for packs.
UPDATE billing_settings SET face_search_main_price = 499, face_search_quick_price = 299;

-- What face search adds to each pack a month (the pack's own price is on top): Main 2 ₹3,499 + ₹500 = ₹3,999,
-- Main 4 ₹4,999 + ₹1,000 = ₹5,999, Quick 2 ₹2,499 + ₹500 = ₹2,999, Quick 4 ₹3,999 + ₹1,000 = ₹4,999.
ALTER TABLE billing_settings
    ADD COLUMN face_pack_main2_price  INT NOT NULL DEFAULT 500,
    ADD COLUMN face_pack_main4_price  INT NOT NULL DEFAULT 1000,
    ADD COLUMN face_pack_quick2_price INT NOT NULL DEFAULT 500,
    ADD COLUMN face_pack_quick4_price INT NOT NULL DEFAULT 1000;

-- Paid month by month, next to the pack's own plan_paid_until.
ALTER TABLE admin_users ADD COLUMN face_pack_paid_until DATE;

-- What face search really used, per event and day (India time), to compare with the AWS bill. No foreign key: the
-- numbers stay after an event is deleted.
CREATE TABLE face_usage (
    event_id       UUID NOT NULL,
    day            DATE NOT NULL,
    photos_scanned INT  NOT NULL DEFAULT 0,
    faces_indexed  INT  NOT NULL DEFAULT 0,
    searches       INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, day)
);
CREATE INDEX ix_face_usage_day ON face_usage (day);
