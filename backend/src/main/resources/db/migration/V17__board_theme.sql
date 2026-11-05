-- The studio's printed board and event cards: one of three designs (cream, dark or white).
ALTER TABLE admin_users ADD COLUMN board_theme VARCHAR(10) NOT NULL DEFAULT 'cream'
    CHECK (board_theme IN ('cream', 'dark', 'white'));
