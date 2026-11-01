package com.mcreatik.gallery.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.common.ApiException;

/**
 * Studio plans and event allowances. MONTHLY: {@code monthly_quota} events each calendar month (India time), then
 * paid extra events; PER_EVENT: every event paid. Extra events are bought by UPI: credited straight away, then
 * checked by McreatiK (approved, or rejected and taken back). Bought events never expire.
 */
@Service
public class BillingService {

    public enum Plan { MONTHLY, PER_EVENT, UNLIMITED }

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_EVENTS_PER_PAYMENT = 50;

    public record Settings(String upiId, String payeeName, int perEventPrice, int extraEventPrice, int monthlyPrice) {
    }

    public record Payment(UUID id, UUID studioId, String studioName, int events, int unitPrice, int amount, String utr,
                          String status, String note, Instant createdAt, Instant reviewedAt) {
    }

    /** {@code free}: McreatiK itself or an unlimited studio; {@code unitPrice}: what one more event costs. */
    public record Summary(Plan plan, boolean free, int monthlyQuota, int usedThisMonth, int credits, int unitPrice,
                          int monthlyPrice, String upiId, String payeeName, List<Payment> payments) {
    }

    public record StudioBilling(UUID studioId, Plan plan, int monthlyQuota, int usedThisMonth, int credits) {
    }

    private final JdbcTemplate jdbc;

    public BillingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Account(String role, Plan plan, int quota, int credits) {
        boolean free() {
            return "SUPER_ADMIN".equals(role) || plan == Plan.UNLIMITED;
        }
    }

    private Account account(UUID studioId, boolean lock) {
        List<Account> rows = jdbc.query("SELECT role, plan, monthly_quota, event_credits FROM admin_users WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Account(rs.getString(1), Plan.valueOf(rs.getString(2)),
                rs.getInt(3), rs.getInt(4)), studioId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Studio");
        }
        return rows.getFirst();
    }

    static Instant monthStart() {
        return LocalDate.now(INDIA).withDayOfMonth(1).atStartOfDay(INDIA).toInstant();
    }

    private int usedThisMonth(UUID studioId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_charges WHERE owner_id = ? AND kind = 'QUOTA' AND created_at >= ?",
                Integer.class, studioId, Timestamp.from(monthStart()));
    }

    public Settings settings() {
        return jdbc.queryForObject("SELECT upi_id, payee_name, per_event_price, extra_event_price, monthly_price FROM billing_settings",
                (rs, n) -> new Settings(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
    }

    public Settings updateSettings(Settings s) {
        String upi = s.upiId() == null ? null : s.upiId().trim();
        if (upi != null && !upi.isEmpty() && !upi.matches("^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$")) {
            throw ApiException.badRequest("INVALID_UPI", "Enter a UPI ID like mcreatik@okicici");
        }
        if (s.perEventPrice() < 1 || s.extraEventPrice() < 1 || s.monthlyPrice() < 1) {
            throw ApiException.badRequest("INVALID_PRICE", "Prices must be positive");
        }
        jdbc.update("UPDATE billing_settings SET upi_id = ?, payee_name = ?, per_event_price = ?, extra_event_price = ?, monthly_price = ?",
                upi == null || upi.isEmpty() ? null : upi, s.payeeName() == null ? null : s.payeeName().trim(),
                s.perEventPrice(), s.extraEventPrice(), s.monthlyPrice());
        return settings();
    }

    private int unitPrice(Plan plan, Settings s) {
        return plan == Plan.MONTHLY ? s.extraEventPrice() : s.perEventPrice();
    }

    /**
     * Takes one event from the studio's allowance (monthly quota first, then bought events) or refuses with 402.
     * Must run inside the transaction that creates the event; {@link #recordCharge} completes it.
     */
    public String reserveEvent(UUID studioId) {
        Account a = account(studioId, true);
        if (a.free()) {
            return "FREE";
        }
        if (a.plan() == Plan.MONTHLY && usedThisMonth(studioId) < a.quota()) {
            return "QUOTA";
        }
        if (a.credits() > 0) {
            jdbc.update("UPDATE admin_users SET event_credits = event_credits - 1 WHERE id = ?", studioId);
            return "CREDIT";
        }
        int price = unitPrice(a.plan(), settings());
        String why = a.plan() == Plan.MONTHLY
                ? "You have used your " + a.quota() + (a.quota() == 1 ? " event" : " events")
                        + " for this month. Extra events are ₹" + price + " each."
                : "Each event is ₹" + price + ". Buy an event to create it.";
        throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "EVENT_PAYMENT_REQUIRED", why);
    }

    public void recordCharge(UUID studioId, UUID eventId, String kind) {
        jdbc.update("INSERT INTO event_charges (id, owner_id, event_id, kind) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), studioId, eventId, kind);
    }

    public Summary summary(UUID studioId) {
        Account a = account(studioId, false);
        Settings s = settings();
        return new Summary(a.plan(), a.free(), a.quota(), usedThisMonth(studioId), a.credits(), unitPrice(a.plan(), s),
                s.monthlyPrice(), s.upiId(), s.payeeName(), payments("p.studio_id = ?", studioId, 20));
    }

    /** The studio paid by UPI and reports the transaction: the events are usable at once, checked later. */
    @Transactional
    public Payment submitPayment(UUID studioId, int events, String rawUtr) {
        Account a = account(studioId, true);
        if (a.free()) {
            throw ApiException.badRequest("NOT_NEEDED", "Your plan has no event limit");
        }
        if (events < 1 || events > MAX_EVENTS_PER_PAYMENT) {
            throw ApiException.badRequest("INVALID_EVENTS", "Buy between 1 and " + MAX_EVENTS_PER_PAYMENT + " events at a time");
        }
        String utr = rawUtr == null ? "" : rawUtr.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!utr.matches("^[A-Z0-9]{8,30}$")) {
            throw ApiException.badRequest("INVALID_UTR", "Enter the UPI transaction ID (UTR / reference number) from your payment app");
        }
        Settings s = settings();
        if (s.upiId() == null) {
            throw ApiException.conflict("UPI_NOT_SET", "Payments are not set up yet. Please contact McreatiK.");
        }
        int price = unitPrice(a.plan(), s);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO event_payments (id, studio_id, events, unit_price, amount, utr) VALUES (?, ?, ?, ?, ?, ?)",
                    id, studioId, events, price, events * price, utr);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("UTR_USED", "This transaction ID was already submitted");
        }
        jdbc.update("UPDATE admin_users SET event_credits = event_credits + ? WHERE id = ?", events, studioId);
        return payments("p.id = ?", id, 1).getFirst();
    }

    public List<Payment> payments(String status) {
        return status == null || status.isBlank()
                ? payments("TRUE", null, 200)
                : payments("p.status = ?", status.toUpperCase(Locale.ROOT), 200);
    }

    private List<Payment> payments(String where, Object arg, int limit) {
        String sql = """
                SELECT p.id, p.studio_id, u.display_name, p.events, p.unit_price, p.amount, p.utr, p.status, p.note,
                       p.created_at, p.reviewed_at
                FROM event_payments p JOIN admin_users u ON u.id = p.studio_id
                WHERE %s ORDER BY p.created_at DESC LIMIT %d""".formatted(where, limit);
        Object[] args = arg == null ? new Object[0] : new Object[] {arg};
        return jdbc.query(sql, (rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()), args);
    }

    /** Found in the bank statement: confirmed. */
    public void approve(UUID paymentId) {
        if (jdbc.update("UPDATE event_payments SET status = 'APPROVED', reviewed_at = now() WHERE id = ? AND status = 'PENDING'",
                paymentId) == 0) {
            throw ApiException.conflict("NOT_PENDING", "This payment was already reviewed");
        }
    }

    /** Not received: the events are taken back (the balance may go below zero until it is sorted out). */
    @Transactional
    public void reject(UUID paymentId, String note) {
        List<Object[]> rows = jdbc.query("SELECT studio_id, events FROM event_payments WHERE id = ? AND status = 'PENDING' FOR UPDATE",
                (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)}, paymentId);
        if (rows.isEmpty()) {
            throw ApiException.conflict("NOT_PENDING", "This payment was already reviewed");
        }
        jdbc.update("UPDATE event_payments SET status = 'REJECTED', reviewed_at = now(), note = ? WHERE id = ?",
                note == null || note.isBlank() ? null : note.trim(), paymentId);
        jdbc.update("UPDATE admin_users SET event_credits = event_credits - ? WHERE id = ?", rows.getFirst()[1], rows.getFirst()[0]);
    }

    public List<StudioBilling> studios() {
        return jdbc.query("""
                SELECT u.id, u.plan, u.monthly_quota, u.event_credits,
                       (SELECT count(*) FROM event_charges c WHERE c.owner_id = u.id AND c.kind = 'QUOTA' AND c.created_at >= ?)
                FROM admin_users u ORDER BY u.created_at""", (rs, n) -> new StudioBilling(rs.getObject(1, UUID.class),
                Plan.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(5), rs.getInt(4)), Timestamp.from(monthStart()));
    }

    /** McreatiK sets a studio's plan and monthly allowance, and can give (or take back) events. */
    @Transactional
    public StudioBilling updateStudio(UUID studioId, Plan plan, Integer monthlyQuota, Integer addCredits) {
        account(studioId, true);
        if (plan != null) {
            jdbc.update("UPDATE admin_users SET plan = ? WHERE id = ?", plan.name(), studioId);
        }
        if (monthlyQuota != null) {
            if (monthlyQuota < 0 || monthlyQuota > 1000) {
                throw ApiException.badRequest("INVALID_QUOTA", "The monthly allowance must be between 0 and 1000");
            }
            jdbc.update("UPDATE admin_users SET monthly_quota = ? WHERE id = ?", monthlyQuota, studioId);
        }
        if (addCredits != null && addCredits != 0) {
            jdbc.update("UPDATE admin_users SET event_credits = event_credits + ? WHERE id = ?", addCredits, studioId);
        }
        return studios().stream().filter(s -> s.studioId().equals(studioId)).findFirst().orElseThrow();
    }
}
