-- UPI QR confirmed automatically: a single-use Razorpay QR for the exact amount (method UPI_AUTO), no UTR to type.
ALTER TABLE event_payments ADD COLUMN razorpay_qr_id VARCHAR(40) UNIQUE;
ALTER TABLE event_payments ADD COLUMN expires_at TIMESTAMPTZ;
