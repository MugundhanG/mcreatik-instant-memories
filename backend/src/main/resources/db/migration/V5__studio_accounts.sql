-- Studio accounts: McreatiK (super admin) creates logins for client studios. Each studio sees only its own
-- events; the super admin sees all of them. display_name is the studio's name.
ALTER TABLE admin_users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'STUDIO';
ALTER TABLE admin_users ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE admin_users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- Everyone who could log in before studio accounts existed is the platform owner.
UPDATE admin_users SET role = 'SUPER_ADMIN';
