-- Studio branding shown on the studio's guest galleries and posters ("Powered by McreatiK").
ALTER TABLE admin_users
    ADD COLUMN instagram_handle VARCHAR(30),
    ADD COLUMN logo_key         VARCHAR(300);
