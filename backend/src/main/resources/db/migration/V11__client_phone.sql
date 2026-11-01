-- The couple's WhatsApp number, so the studio can send their login again from the event page.
ALTER TABLE event_clients ADD COLUMN phone VARCHAR(20);
