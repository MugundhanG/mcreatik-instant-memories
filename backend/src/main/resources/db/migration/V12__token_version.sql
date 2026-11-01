-- Changing or resetting a password logs out every other device: tokens carry this number and stop working
-- once it moves on. Tokens issued before this column existed count as version 0.
ALTER TABLE admin_users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE event_clients ADD COLUMN token_version INT NOT NULL DEFAULT 0;
