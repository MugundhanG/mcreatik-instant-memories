-- Online payments (Razorpay checkout: card, netbanking, UPI, wallet) next to the manual UPI QR + UTR.
ALTER TABLE event_payments ADD COLUMN method VARCHAR(10) NOT NULL DEFAULT 'UPI_QR';
ALTER TABLE event_payments ALTER COLUMN utr DROP NOT NULL;
ALTER TABLE event_payments ADD COLUMN razorpay_order_id VARCHAR(40) UNIQUE;
ALTER TABLE event_payments ADD COLUMN razorpay_payment_id VARCHAR(40) UNIQUE;
-- status: PENDING / APPROVED / REJECTED (UPI QR), CREATED -> APPROVED (online, approved when Razorpay confirms the payment)
