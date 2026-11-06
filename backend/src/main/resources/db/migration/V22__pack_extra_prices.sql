-- Extra events on a paid-up monthly pack: what each event past the pack's monthly allowance costs.
-- A Main pack's price covers Main and Quick extras; a Quick pack's covers Quick extras only.
ALTER TABLE billing_settings
    ADD COLUMN main2_extra_price  INT NOT NULL DEFAULT 1499,
    ADD COLUMN main4_extra_price  INT NOT NULL DEFAULT 999,
    ADD COLUMN quick2_extra_price INT NOT NULL DEFAULT 999,
    ADD COLUMN quick4_extra_price INT NOT NULL DEFAULT 799;
