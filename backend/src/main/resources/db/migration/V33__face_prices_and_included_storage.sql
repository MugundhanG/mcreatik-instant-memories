-- Agreed pricing (Nov 2026): face search ₹699 per Main / ₹399 per Quick event, and storage included per event
-- type: 25 GB with a Main event, 10 GB with a Quick event (free_storage_gb now means Main).
UPDATE billing_settings SET face_search_main_price = 699, face_search_quick_price = 399, free_storage_gb = 25;
ALTER TABLE billing_settings ADD COLUMN free_storage_gb_quick INT NOT NULL DEFAULT 10;

-- Events whose photos are still kept get the new allowance (never less than they already have) ...
UPDATE events SET storage_quota_gb = GREATEST(storage_quota_gb, CASE event_type WHEN 'MAIN' THEN 25 ELSE 10 END)
WHERE event_type IN ('MAIN', 'QUICK') AND photos_deleted_at IS NULL;

-- ... and a gallery locked only for unpaid storage that the new allowance now covers opens again
-- (the same rule as BillingService.unlockIfCovered).
UPDATE events e SET client_gallery_enabled = true, client_gallery_locked = false, storage_locked = false
WHERE e.storage_locked AND e.photos_deleted_at IS NULL
  AND e.storage_quota_gb::bigint * 1073741824 >= (
      SELECT coalesce(sum(p.media_bytes + CASE WHEN p.original_stored THEN p.file_size ELSE 0 END), 0)
      FROM photos p WHERE p.event_id = e.id AND p.status = 'READY');
