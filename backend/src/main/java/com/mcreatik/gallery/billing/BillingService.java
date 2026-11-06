package com.mcreatik.gallery.billing;

import java.sql.Date;
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
import com.mcreatik.gallery.event.EventType;

/**
 * Studio plans, event allowances and everything a studio buys.
 * <ul>
 * <li><b>Plans:</b> PER_EVENT (every event bought), the monthly packs MAIN_2 / MAIN_4 / QUICK_2 / QUICK_4 (2 or 4
 * events each calendar month, India time), UNLIMITED, and the older MONTHLY ({@code monthly_quota} events, then
 * extra events at {@code extra_event_price}).</li>
 * <li><b>Main covers Quick:</b> a Main pack or a Main credit also covers a Quick event; a Quick pack or credit never
 * covers a Main event.</li>
 * <li><b>Packs are paid month by month</b> ({@code plan_paid_until}); the allowance stops {@value #GRACE_DAYS} days
 * after that date unless renewed. Bought events never expire.</li>
 * <li><b>Carry-over:</b> a pack's unused events of last month can be used this month (one month only), counted
 * from the event charges, so nothing extra is stored.</li>
 * </ul>
 * Purchases ({@link Item}) are paid by UPI QR (applied straight away, then checked by McreatiK: approved, or rejected
 * and reversed) or online with Razorpay ({@link OnlinePaymentService}: applied when Razorpay confirms). The price is
 * always the server's.
 */
@Service
public class BillingService {

    public enum Plan {
        PER_EVENT, MAIN_2, MAIN_4, QUICK_2, QUICK_4, MONTHLY, UNLIMITED;

        /** A monthly pack paid month by month. */
        public boolean pack() {
            return this == MAIN_2 || this == MAIN_4 || this == QUICK_2 || this == QUICK_4;
        }

        int monthlyEvents(int legacyQuota) {
            return switch (this) {
                case MAIN_2, QUICK_2 -> 2;
                case MAIN_4, QUICK_4 -> 4;
                case MONTHLY -> legacyQuota;
                default -> 0;
            };
        }

        /** Whether this month's allowance can be used for an event of this type. */
        boolean covers(EventType type) {
            return switch (this) {
                case MAIN_2, MAIN_4, MONTHLY -> type == EventType.MAIN || type == EventType.QUICK;
                case QUICK_2, QUICK_4 -> type == EventType.QUICK;
                default -> false;
            };
        }

        String label() {
            return switch (this) {
                case MAIN_2 -> "Main 2-event";
                case MAIN_4 -> "Main 4-event";
                case QUICK_2 -> "Quick 2-event";
                case QUICK_4 -> "Quick 4-event";
                default -> "monthly";
            };
        }
    }

    /**
     * What a payment buys. STORAGE: GB for one event, priced per GB per month for the months the gallery has left;
     * CLIENT_SETUP: client galleries (one used per gallery created); EXTEND: another open period (active_months) for one event, per GB it
     * holds per month.
     */
    public enum Item { MAIN_EVENT, QUICK_EVENT, UPGRADE, PLAN, STORAGE, CLIENT_SETUP, EXTEND }

    /** One GB as storage is counted and sold (1024^3 bytes). */
    public static final long GB = 1024L * 1024 * 1024;
    static final int MAX_GB_PER_PAYMENT = 1000;

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_EVENTS_PER_PAYMENT = 50;
    static final int MAX_MONTHS_PER_PAYMENT = 12;
    /** A pack keeps working this many days after its paid-until date (time to renew). */
    static final int GRACE_DAYS = 3;

    /**
     * Prices and the UPI details. {@code perEventPrice} is the single Main event price; {@code extraEventPrice} and
     * {@code monthlyPrice} belong to the older MONTHLY plan. When saving, a missing (null) newer price keeps its value.
     */
    public record Settings(String upiId, String payeeName, int perEventPrice, int extraEventPrice, int monthlyPrice,
                           Integer quickPrice, Integer main2Price, Integer main4Price, Integer quick2Price,
                           Integer quick4Price, Integer upgradePrice, Integer upgradeGuests, Integer storageGbPrice,
                           Integer freeStorageGb, Integer clientSetupPrice, Integer activeMonths) {

        int packPrice(Plan plan) {
            return switch (plan) {
                case MAIN_2 -> main2Price;
                case MAIN_4 -> main4Price;
                case QUICK_2 -> quick2Price;
                case QUICK_4 -> quick4Price;
                case MONTHLY -> monthlyPrice;
                default -> 0;
            };
        }
    }

    /** {@code method}: UPI_QR (checked by McreatiK), RAZORPAY or UPI_AUTO (confirmed by Razorpay). */
    public record Payment(UUID id, UUID studioId, String studioName, int events, int unitPrice, int amount, String utr,
                          String status, String note, Instant createdAt, Instant reviewedAt, String method,
                          String razorpayPaymentId, Item item, UUID eventId, String eventName, Plan plan) {
    }

    /** Every current price, for the dashboard (the server prices each purchase again). */
    public record Prices(int main, int quick, int main2, int main4, int quick2, int quick4, int upgrade, int upgradeGuests,
                         int storageGb, int freeStorageGb, int clientSetup, int activeMonths) {
    }

    /**
     * {@code free}: McreatiK itself or an unlimited studio. {@code monthlyQuota} / {@code usedThisMonth}: the pack's
     * events this month; {@code carryOver}: last month's unused events still usable. {@code credits} is
     * {@code mainCredits + quickCredits}; {@code unitPrice} is what one more Main event costs; {@code monthlyPrice}
     * what this plan costs a month. {@code packActive}: paid up (or within the grace days).
     */
    public record Summary(Plan plan, boolean free, int monthlyQuota, int usedThisMonth, int credits, int unitPrice,
                          int monthlyPrice, String upiId, String payeeName, List<Payment> payments, boolean onlinePayments,
                          int mainCredits, int quickCredits, int carryOver, boolean packActive, LocalDate planPaidUntil,
                          Prices prices, int clientCredits) {
    }

    /** What a purchase costs this studio right now. {@code events}: how many (events or months). */
    public record Quote(Item item, int events, int unitPrice, int amount, UUID eventId, Plan plan) {
    }

    /** A purchase as asked for; {@link #normalize} fills the defaults. */
    public record Purchase(Item item, int quantity, UUID eventId, Plan plan) {
    }

    public record StudioBilling(UUID studioId, Plan plan, int monthlyQuota, int usedThisMonth, int credits,
                                int mainCredits, int quickCredits, LocalDate planPaidUntil, boolean packActive,
                                int clientCredits) {
    }

    private final JdbcTemplate jdbc;
    private final RazorpayProperties razorpay;

    public BillingService(JdbcTemplate jdbc, RazorpayProperties razorpay) {
        this.jdbc = jdbc;
        this.razorpay = razorpay;
    }

    record Account(String role, Plan plan, int legacyQuota, int mainCredits, int quickCredits, LocalDate paidUntil,
                   LocalDate planSince, int clientCredits) {
        boolean free() {
            return "SUPER_ADMIN".equals(role) || plan == Plan.UNLIMITED;
        }

        int monthlyEvents() {
            return plan.monthlyEvents(legacyQuota);
        }

        /** MONTHLY studios were never asked to renew in the app, so their allowance doesn't lapse. */
        boolean packActive(LocalDate today) {
            if (plan == Plan.MONTHLY) {
                return true;
            }
            return plan.pack() && paidUntil != null && !today.isAfter(paidUntil.plusDays(GRACE_DAYS));
        }
    }

    private Account account(UUID studioId, boolean lock) {
        List<Account> rows = jdbc.query("""
                SELECT role, plan, monthly_quota, main_credits, quick_credits, plan_paid_until, plan_since, client_credits
                FROM admin_users WHERE id = ?""" + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Account(rs.getString(1),
                Plan.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getDate(7).toLocalDate(), rs.getInt(8)), studioId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Studio");
        }
        return rows.getFirst();
    }

    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    static Instant monthStart() {
        return monthStart(today());
    }

    private static Instant monthStart(LocalDate day) {
        return day.withDayOfMonth(1).atStartOfDay(INDIA).toInstant();
    }

    private int charges(UUID studioId, String kind, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*) FROM event_charges WHERE owner_id = ? AND kind = ? AND created_at >= ? AND created_at < ?",
                Integer.class, studioId, kind, Timestamp.from(from), Timestamp.from(to));
    }

    /** This month's pack events used, counted from the day the current plan started (a new pack starts fresh). */
    private int usedThisMonth(UUID studioId, Account a) {
        Instant since = a.planSince().atStartOfDay(INDIA).toInstant();
        Instant from = since.isAfter(monthStart()) ? since : monthStart();
        return charges(studioId, "QUOTA", from, Instant.now().plusSeconds(60));
    }

    /**
     * Last month's unused pack events, minus those already used this month. Only for a pack that was already this
     * studio's plan before this month began (a pack bought or changed this month has no last month).
     */
    int carryOver(UUID studioId, Account a) {
        LocalDate today = today();
        if (!a.plan().pack() || !a.packActive(today) || !a.planSince().isBefore(today.withDayOfMonth(1))) {
            return 0;
        }
        Instant thisMonth = monthStart(today);
        Instant lastMonth = monthStart(today.minusMonths(1));
        int unused = Math.max(0, a.monthlyEvents() - charges(studioId, "QUOTA", lastMonth, thisMonth));
        return Math.max(0, unused - charges(studioId, "CARRY", thisMonth, Instant.now().plusSeconds(60)));
    }

    public Settings settings() {
        return jdbc.queryForObject("""
                SELECT upi_id, payee_name, per_event_price, extra_event_price, monthly_price, quick_price, main2_price,
                       main4_price, quick2_price, quick4_price, upgrade_price, upgrade_guests, storage_gb_price,
                       free_storage_gb, client_setup_price, active_months FROM billing_settings""",
                (rs, n) -> new Settings(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                        rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16)));
    }

    private static int orKeep(Integer value, int current) {
        return value == null ? current : value;
    }

    public Settings updateSettings(Settings s) {
        Settings now = settings();
        String upi = s.upiId() == null ? null : s.upiId().trim();
        if (upi != null && !upi.isEmpty() && !upi.matches("^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$")) {
            throw ApiException.badRequest("INVALID_UPI", "Enter a UPI ID like mcreatik@okicici");
        }
        int[] prices = {s.perEventPrice(), s.extraEventPrice(), s.monthlyPrice(), orKeep(s.quickPrice(), now.quickPrice()),
                orKeep(s.main2Price(), now.main2Price()), orKeep(s.main4Price(), now.main4Price()),
                orKeep(s.quick2Price(), now.quick2Price()), orKeep(s.quick4Price(), now.quick4Price()),
                orKeep(s.upgradePrice(), now.upgradePrice()), orKeep(s.upgradeGuests(), now.upgradeGuests()),
                orKeep(s.storageGbPrice(), now.storageGbPrice()), orKeep(s.freeStorageGb(), now.freeStorageGb()),
                orKeep(s.clientSetupPrice(), now.clientSetupPrice()), orKeep(s.activeMonths(), now.activeMonths())};
        for (int i = 0; i < prices.length; i++) {
            // The free storage may be 0 GB; everything else must be at least 1.
            if (prices[i] < (i == 11 ? 0 : 1)) {
                throw ApiException.badRequest("INVALID_PRICE", "Prices must be positive");
            }
        }
        jdbc.update("""
                UPDATE billing_settings SET upi_id = ?, payee_name = ?, per_event_price = ?, extra_event_price = ?,
                       monthly_price = ?, quick_price = ?, main2_price = ?, main4_price = ?, quick2_price = ?, quick4_price = ?,
                       upgrade_price = ?, upgrade_guests = ?, storage_gb_price = ?, free_storage_gb = ?,
                       client_setup_price = ?, active_months = ?""",
                upi == null || upi.isEmpty() ? null : upi, s.payeeName() == null ? null : s.payeeName().trim(),
                prices[0], prices[1], prices[2], prices[3], prices[4], prices[5], prices[6], prices[7], prices[8], prices[9],
                prices[10], prices[11], prices[12], prices[13]);
        return settings();
    }

    /** What one event of this type costs as a single purchase on this plan. */
    private static int eventPrice(Plan plan, EventType type, Settings s) {
        if (type == EventType.QUICK) {
            return s.quickPrice();
        }
        return plan == Plan.MONTHLY ? s.extraEventPrice() : s.perEventPrice();
    }

    private static String rupees(int amount) {
        return "₹" + String.format(Locale.forLanguageTag("en-IN"), "%,d", amount);
    }

    private static String kindName(EventType type) {
        return type == EventType.QUICK ? "Quick" : "Main";
    }

    /**
     * Covers one new event, in this order: last month's carried-over pack events, this month's pack, a bought
     * credit (a Quick event may use a Main credit). Otherwise refuses with 402. Must run inside the transaction that
     * creates the event; {@link #recordCharge} completes it.
     */
    public String reserveEvent(UUID studioId, EventType type) {
        Account a = account(studioId, true);
        if (a.free()) {
            return "FREE";
        }
        if (type == EventType.CLIENT) {
            if (a.clientCredits() > 0) {
                jdbc.update("UPDATE admin_users SET client_credits = client_credits - 1 WHERE id = ?", studioId);
                return "CLIENT";
            }
            Settings s = settings();
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "CLIENT_PAYMENT_REQUIRED",
                    "A client gallery is " + rupees(s.clientSetupPrice()) + " to set up, plus storage at "
                            + rupees(s.storageGbPrice()) + " per GB a month. Buy one to create it.");
        }
        LocalDate today = today();
        boolean packOk = a.packActive(today) && a.plan().covers(type);
        if (packOk && carryOver(studioId, a) > 0) {
            return "CARRY";
        }
        if (packOk && usedThisMonth(studioId, a) < a.monthlyEvents()) {
            return "QUOTA";
        }
        if (type == EventType.QUICK && a.quickCredits() > 0) {
            jdbc.update("UPDATE admin_users SET quick_credits = quick_credits - 1 WHERE id = ?", studioId);
            return "CREDIT";
        }
        if (a.mainCredits() > 0) {
            jdbc.update("UPDATE admin_users SET main_credits = main_credits - 1 WHERE id = ?", studioId);
            return "CREDIT";
        }
        Settings s = settings();
        String price = "A " + kindName(type) + " event is " + rupees(eventPrice(a.plan(), type, s)) + ".";
        String why;
        if (a.plan() == Plan.MONTHLY && type == EventType.MAIN) {
            why = "You have used your " + a.monthlyEvents() + (a.monthlyEvents() == 1 ? " event" : " events")
                    + " for this month. Extra events are " + rupees(s.extraEventPrice()) + " each.";
        } else if (a.plan().pack() && !a.packActive(today)) {
            why = "Your " + a.plan().label() + " pack ended on " + a.paidUntil() + ". Renew it, or buy this event. " + price;
        } else if (a.plan().pack() && !a.plan().covers(type)) {
            why = "Your Quick pack covers Quick events only. " + price;
        } else if (a.plan().pack() || a.plan() == Plan.MONTHLY) {
            why = "You have used your " + a.monthlyEvents() + " events for this month. " + price;
        } else {
            why = price + " Buy one to create it.";
        }
        throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "EVENT_PAYMENT_REQUIRED", why);
    }

    public void recordCharge(UUID studioId, UUID eventId, String kind) {
        jdbc.update("INSERT INTO event_charges (id, owner_id, event_id, kind) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), studioId, eventId, kind);
    }

    private static Prices prices(Settings s) {
        return new Prices(s.perEventPrice(), s.quickPrice(), s.main2Price(), s.main4Price(), s.quick2Price(),
                s.quick4Price(), s.upgradePrice(), s.upgradeGuests(), s.storageGbPrice(), s.freeStorageGb(),
                s.clientSetupPrice(), s.activeMonths());
    }

    /** The current prices for the public /pricing page. */
    public Prices publicPrices() {
        return prices(settings());
    }

    public Summary summary(UUID studioId) {
        Account a = account(studioId, false);
        Settings s = settings();
        LocalDate today = today();
        return new Summary(a.plan(), a.free(), a.monthlyEvents(), usedThisMonth(studioId, a), a.mainCredits() + a.quickCredits(),
                eventPrice(a.plan(), EventType.MAIN, s), a.plan() == Plan.MONTHLY ? s.monthlyPrice() : s.packPrice(a.plan()),
                s.upiId(), s.payeeName(), payments("p.studio_id = ?", studioId, 20), razorpay.enabled(),
                a.mainCredits(), a.quickCredits(), carryOver(studioId, a), a.packActive(today), a.paidUntil(), prices(s),
                a.clientCredits());
    }

    /** Fills the defaults: a missing item is Main events (the older API sent only a count). */
    public static Purchase normalize(Item item, Integer quantity, UUID eventId, Plan plan) {
        Item i = item == null ? Item.MAIN_EVENT : item;
        int q = quantity == null ? 1 : quantity;
        return new Purchase(i, i == Item.UPGRADE || i == Item.EXTEND ? 1 : q, eventId, plan);
    }

    /** The price is always the server's: the studio's plan, the current settings and what is bought. */
    public Quote quote(UUID studioId, Purchase p) {
        return quote(studioId, account(studioId, false), p);
    }

    private Quote quote(UUID studioId, Account a, Purchase p) {
        Settings s = settings();
        return switch (p.item()) {
            case MAIN_EVENT, QUICK_EVENT -> {
                if (a.free()) {
                    throw ApiException.badRequest("NOT_NEEDED", "Your plan has no event limit");
                }
                if (p.quantity() < 1 || p.quantity() > MAX_EVENTS_PER_PAYMENT) {
                    throw ApiException.badRequest("INVALID_EVENTS", "Buy between 1 and " + MAX_EVENTS_PER_PAYMENT + " events at a time");
                }
                int price = eventPrice(a.plan(), p.item() == Item.QUICK_EVENT ? EventType.QUICK : EventType.MAIN, s);
                yield new Quote(p.item(), p.quantity(), price, p.quantity() * price, null, null);
            }
            case UPGRADE -> {
                if (p.eventId() == null) {
                    throw ApiException.badRequest("INVALID_EVENT", "Choose the event to upgrade");
                }
                List<String> type = jdbc.queryForList("SELECT event_type FROM events WHERE id = ? AND owner_id = ?",
                        String.class, p.eventId(), studioId);
                if (type.isEmpty()) {
                    throw ApiException.notFound("Event");
                }
                if (!EventType.QUICK.name().equals(type.getFirst())) {
                    throw ApiException.conflict("NOT_QUICK", "Only a Quick event can be upgraded to Main");
                }
                yield new Quote(Item.UPGRADE, 1, s.upgradePrice(), s.upgradePrice(), p.eventId(), null);
            }
            case PLAN -> {
                if (a.free()) {
                    throw ApiException.badRequest("NOT_NEEDED", "Your plan has no event limit");
                }
                Plan plan = p.plan() == null ? a.plan() : p.plan();
                if (!plan.pack()) {
                    throw ApiException.badRequest("INVALID_PLAN", "Choose a monthly pack: Main or Quick, 2 or 4 events");
                }
                if (p.quantity() < 1 || p.quantity() > MAX_MONTHS_PER_PAYMENT) {
                    throw ApiException.badRequest("INVALID_MONTHS", "Pay for 1 to " + MAX_MONTHS_PER_PAYMENT + " months at a time");
                }
                int price = s.packPrice(plan);
                yield new Quote(Item.PLAN, p.quantity(), price, p.quantity() * price, null, plan);
            }
            case CLIENT_SETUP -> {
                if (a.free()) {
                    throw ApiException.badRequest("NOT_NEEDED", "Your plan has no event limit");
                }
                if (p.quantity() < 1 || p.quantity() > MAX_EVENTS_PER_PAYMENT) {
                    throw ApiException.badRequest("INVALID_EVENTS", "Buy between 1 and " + MAX_EVENTS_PER_PAYMENT + " client galleries at a time");
                }
                yield new Quote(Item.CLIENT_SETUP, p.quantity(), s.clientSetupPrice(), p.quantity() * s.clientSetupPrice(), null, null);
            }
            case STORAGE -> {
                if (a.free()) {
                    throw ApiException.badRequest("NOT_NEEDED", "Your plan has no storage limit");
                }
                ownedEvent(studioId, p.eventId());
                if (p.quantity() < 1 || p.quantity() > MAX_GB_PER_PAYMENT) {
                    throw ApiException.badRequest("INVALID_GB", "Add between 1 and " + MAX_GB_PER_PAYMENT + " GB at a time");
                }
                int perGb = s.storageGbPrice() * monthsLeft(p.eventId(), s.activeMonths());
                yield new Quote(Item.STORAGE, p.quantity(), perGb, p.quantity() * perGb, p.eventId(), null);
            }
            case EXTEND -> {
                if (a.free()) {
                    throw ApiException.badRequest("NOT_NEEDED", "Your plan has no time limit: change the date in the event settings");
                }
                ownedEvent(studioId, p.eventId());
                Boolean deleted = jdbc.queryForObject("SELECT retention_until IS NOT NULL AND retention_until < ? FROM events WHERE id = ?",
                        Boolean.class, Date.valueOf(today()), p.eventId());
                if (Boolean.TRUE.equals(deleted)) {
                    throw ApiException.conflict("EVENT_DELETED", "This gallery's files have been deleted");
                }
                int gb = gbFor(eventBytes(p.eventId()));
                int perGb = s.storageGbPrice() * s.activeMonths();
                yield new Quote(Item.EXTEND, gb, perGb, gb * perGb, p.eventId(), null);
            }
        };
    }

    private void ownedEvent(UUID studioId, UUID eventId) {
        if (eventId == null) {
            throw ApiException.badRequest("INVALID_EVENT", "Choose the event");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM events WHERE id = ? AND owner_id = ?", Integer.class, eventId, studioId) == 0) {
            throw ApiException.notFound("Event");
        }
    }

    /**
     * Months storage is charged for when bought now: what is left of the gallery's open period, whole months rounded
     * up (a new gallery: its full period), at least 1. A gallery with no end date is charged the full period.
     */
    int monthsLeft(UUID eventId, int activeMonths) {
        Date until = jdbc.queryForObject("SELECT active_until FROM events WHERE id = ?", Date.class, eventId);
        return monthsLeft(until == null ? null : until.toLocalDate(), today(), activeMonths);
    }

    public static int monthsLeft(LocalDate activeUntil, LocalDate today, int activeMonths) {
        if (activeUntil == null) {
            return Math.max(1, activeMonths);
        }
        if (!activeUntil.isAfter(today)) {
            return 1;
        }
        int months = (int) java.time.temporal.ChronoUnit.MONTHS.between(today, activeUntil);
        return today.plusMonths(months).isBefore(activeUntil) ? months + 1 : Math.max(1, months);
    }

    /** Whole GB to charge for this many bytes (at least 1). */
    public static int gbFor(long bytes) {
        return (int) Math.max(1, (bytes + GB - 1) / GB);
    }

    /** Everything an event stores: web versions, thumbnails and the kept full-quality originals. */
    public long eventBytes(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(media_bytes + CASE WHEN original_stored THEN file_size ELSE 0 END), 0)
                FROM photos WHERE event_id = ?""", Long.class, eventId);
    }

    /** McreatiK itself or an unlimited studio: no prices, no storage limit, dates set by hand. */
    public boolean freeAccount(UUID studioId) {
        return account(studioId, false).free();
    }

    /** The couple's gallery of a live event, locked for unpaid storage, comes back once its storage is covered. */
    void unlockIfCovered(UUID eventId) {
        jdbc.update("""
                UPDATE events SET client_gallery_enabled = true, storage_locked = false
                WHERE id = ? AND storage_locked AND storage_quota_gb::bigint * ? >= ?""", eventId, GB, eventBytes(eventId));
    }

    /**
     * Gives (sign 1) or takes back (sign -1, a rejected payment) what a payment bought. Runs in the caller's
     * transaction, right after the payment row changed state, so it happens exactly once.
     */
    void apply(UUID studioId, Item item, int quantity, UUID eventId, Plan plan, int sign) {
        switch (item) {
            case MAIN_EVENT -> jdbc.update("UPDATE admin_users SET main_credits = main_credits + ? WHERE id = ?", sign * quantity, studioId);
            case QUICK_EVENT -> jdbc.update("UPDATE admin_users SET quick_credits = quick_credits + ? WHERE id = ?", sign * quantity, studioId);
            case UPGRADE -> jdbc.update("UPDATE events SET event_type = ?, updated_at = now() WHERE id = ? AND owner_id = ?",
                    sign > 0 ? EventType.MAIN.name() : EventType.QUICK.name(), eventId, studioId);
            case CLIENT_SETUP -> jdbc.update("UPDATE admin_users SET client_credits = client_credits + ? WHERE id = ?", sign * quantity, studioId);
            case STORAGE -> {
                jdbc.update("UPDATE events SET storage_quota_gb = GREATEST(0, storage_quota_gb + ?) WHERE id = ? AND owner_id = ?",
                        sign * quantity, eventId, studioId);
                if (sign > 0) {
                    unlockIfCovered(eventId);
                }
            }
            case EXTEND -> {
                int months = settings().activeMonths();
                // Six more months from the current end (or from today, if it already ended); deleted 30 days after.
                if (sign > 0) {
                    jdbc.update("""
                            UPDATE events SET active_until = (GREATEST(coalesce(active_until, ?), ?) + make_interval(months => ?))::date
                            WHERE id = ? AND owner_id = ?""", Date.valueOf(today()), Date.valueOf(today()), months, eventId, studioId);
                } else {
                    jdbc.update("""
                            UPDATE events SET active_until = (active_until - make_interval(months => ?))::date
                            WHERE id = ? AND owner_id = ? AND active_until IS NOT NULL""", months, eventId, studioId);
                }
                jdbc.update("UPDATE events SET retention_until = (active_until + interval '30 days')::date, expiry_warned_for = NULL,"
                        + " expiry_notified_for = NULL WHERE id = ? AND active_until IS NOT NULL", eventId);
            }
            case PLAN -> {
                if (sign > 0) {
                    // A different pack starts today; the same pack extends from its paid-until date.
                    jdbc.update("""
                            UPDATE admin_users SET
                                plan_since = CASE WHEN plan = ? THEN plan_since ELSE ? END,
                                plan_paid_until = ((CASE WHEN plan = ? AND plan_paid_until >= ? THEN plan_paid_until ELSE ? END)
                                                  + make_interval(months => ?))::date,
                                plan = ?
                            WHERE id = ?""", plan.name(), Date.valueOf(today()), plan.name(), Date.valueOf(today()),
                            Date.valueOf(today()), quantity, plan.name(), studioId);
                } else {
                    jdbc.update("UPDATE admin_users SET plan_paid_until = (plan_paid_until - make_interval(months => ?))::date WHERE id = ?",
                            quantity, studioId);
                }
            }
        }
    }

    /** The studio paid by UPI and reports the transaction: what it bought is usable at once, checked later. */
    @Transactional
    public Payment submitPayment(UUID studioId, Purchase purchase, String rawUtr) {
        Account a = account(studioId, true);
        Quote q = quote(studioId, a, purchase);
        String utr = rawUtr == null ? "" : rawUtr.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!utr.matches("^[A-Z0-9]{8,30}$")) {
            throw ApiException.badRequest("INVALID_UTR", "Enter the UPI transaction ID (UTR / reference number) from your payment app");
        }
        Settings s = settings();
        if (s.upiId() == null) {
            throw ApiException.conflict("UPI_NOT_SET", "Payments are not set up yet. Please contact McreatiK.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO event_payments (id, studio_id, events, unit_price, amount, utr, item, event_id, plan)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, studioId, q.events(), q.unitPrice(), q.amount(), utr,
                    q.item().name(), q.eventId(), q.plan() == null ? null : q.plan().name());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("UTR_USED", "This transaction ID was already submitted");
        }
        apply(studioId, q.item(), q.events(), q.eventId(), q.plan(), 1);
        return payments("p.id = ?", id, 1).getFirst();
    }

    /** Records an online (Razorpay) payment waiting to be paid: nothing is given until {@link #markPaid}. */
    void insertOnline(UUID id, UUID studioId, Quote q, String method, Timestamp expiresAt) {
        jdbc.update("""
                INSERT INTO event_payments (id, studio_id, events, unit_price, amount, method, status, expires_at, item, event_id, plan)
                VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?)""", id, studioId, q.events(), q.unitPrice(), q.amount(),
                method, expiresAt, q.item().name(), q.eventId(), q.plan() == null ? null : q.plan().name());
    }

    private record Bought(UUID studioId, Item item, int quantity, UUID eventId, Plan plan) {
    }

    private static Bought bought(java.sql.ResultSet rs) throws java.sql.SQLException {
        String plan = rs.getString(5);
        return new Bought(rs.getObject(1, UUID.class), Item.valueOf(rs.getString(2)), rs.getInt(3),
                rs.getObject(4, UUID.class), plan == null ? null : Plan.valueOf(plan));
    }

    /**
     * Razorpay confirmed an online payment: marked paid and applied together, and only by whichever confirm, poll or
     * webhook gets there first. Returns false when it was already paid.
     */
    @Transactional
    public boolean markPaid(UUID paymentId, String razorpayPaymentId) {
        List<Bought> rows = jdbc.query("""
                UPDATE event_payments SET status = 'APPROVED', razorpay_payment_id = ?, reviewed_at = now(), note = NULL
                WHERE id = ? AND status = 'CREATED' RETURNING studio_id, item, events, event_id, plan""",
                (rs, n) -> bought(rs), razorpayPaymentId, paymentId);
        if (rows.isEmpty()) {
            return false;
        }
        Bought b = rows.getFirst();
        apply(b.studioId(), b.item(), b.quantity(), b.eventId(), b.plan(), 1);
        return true;
    }

    /** Main and Quick events the studio has bought (or been given) and not used yet. */
    public int credits(UUID studioId) {
        return jdbc.queryForObject("SELECT main_credits + quick_credits FROM admin_users WHERE id = ?", Integer.class, studioId);
    }

    public List<Payment> payments(String status) {
        return status == null || status.isBlank()
                ? payments("TRUE", null, 200)
                : payments("p.status = ?", status.toUpperCase(Locale.ROOT), 200);
    }

    private List<Payment> payments(String where, Object arg, int limit) {
        String sql = """
                SELECT p.id, p.studio_id, u.display_name, p.events, p.unit_price, p.amount, p.utr, p.status, p.note,
                       p.created_at, p.reviewed_at, p.method, p.razorpay_payment_id, p.item, p.event_id, e.name, p.plan
                FROM event_payments p JOIN admin_users u ON u.id = p.studio_id LEFT JOIN events e ON e.id = p.event_id
                WHERE p.status <> 'CREATED' AND %s ORDER BY p.created_at DESC LIMIT %d""".formatted(where, limit);
        Object[] args = arg == null ? new Object[0] : new Object[] {arg};
        return jdbc.query(sql, (rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), rs.getString(12), rs.getString(13),
                Item.valueOf(rs.getString(14)), rs.getObject(15, UUID.class), rs.getString(16),
                rs.getString(17) == null ? null : Plan.valueOf(rs.getString(17))), args);
    }

    /** Found in the bank statement: confirmed. */
    public void approve(UUID paymentId) {
        if (jdbc.update("UPDATE event_payments SET status = 'APPROVED', reviewed_at = now() WHERE id = ? AND status = 'PENDING'",
                paymentId) == 0) {
            throw ApiException.conflict("NOT_PENDING", "This payment was already reviewed");
        }
    }

    /** Not received: what it bought is taken back (an event balance may go below zero until it is sorted out). */
    @Transactional
    public void reject(UUID paymentId, String note) {
        List<Bought> rows = jdbc.query("SELECT studio_id, item, events, event_id, plan FROM event_payments WHERE id = ? AND status = 'PENDING' FOR UPDATE",
                (rs, n) -> bought(rs), paymentId);
        if (rows.isEmpty()) {
            throw ApiException.conflict("NOT_PENDING", "This payment was already reviewed");
        }
        jdbc.update("UPDATE event_payments SET status = 'REJECTED', reviewed_at = now(), note = ? WHERE id = ?",
                note == null || note.isBlank() ? null : note.trim(), paymentId);
        Bought b = rows.getFirst();
        apply(b.studioId(), b.item(), b.quantity(), b.eventId(), b.plan(), -1);
    }

    public List<StudioBilling> studios() {
        LocalDate today = today();
        return jdbc.query("""
                SELECT u.id, u.plan, u.monthly_quota, u.main_credits, u.quick_credits, u.plan_paid_until, u.role, u.plan_since,
                       (SELECT count(*) FROM event_charges c WHERE c.owner_id = u.id AND c.kind = 'QUOTA'
                          AND c.created_at >= GREATEST(?, u.plan_since::timestamp AT TIME ZONE 'Asia/Kolkata')),
                       u.client_credits
                FROM admin_users u ORDER BY u.created_at""", (rs, n) -> {
            Account a = new Account(rs.getString(7), Plan.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getDate(8).toLocalDate(), rs.getInt(10));
            return new StudioBilling(rs.getObject(1, UUID.class), a.plan(), a.monthlyEvents(), rs.getInt(9),
                    a.mainCredits() + a.quickCredits(), a.mainCredits(), a.quickCredits(), a.paidUntil(), a.packActive(today),
                    a.clientCredits());
        }, Timestamp.from(monthStart()));
    }

    public StudioBilling updateStudio(UUID studioId, Plan plan, Integer monthlyQuota, Integer addCredits) {
        return updateStudio(studioId, plan, monthlyQuota, addCredits, null, null, null);
    }

    /**
     * McreatiK sets a studio's plan, the older MONTHLY allowance or a pack's paid-until date, and can give (or take
     * back) Main or Quick events. Moving a studio onto a pack counts as its first month paid unless a date is given.
     */
    @Transactional
    public StudioBilling updateStudio(UUID studioId, Plan plan, Integer monthlyQuota, Integer addCredits,
                                      Integer addQuickCredits, LocalDate planPaidUntil, Integer addClientCredits) {
        Account before = account(studioId, true);
        if (plan != null && plan != before.plan()) {
            jdbc.update("UPDATE admin_users SET plan = ?, plan_since = ? WHERE id = ?", plan.name(), Date.valueOf(today()), studioId);
            if (plan.pack() && planPaidUntil == null) {
                jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?", Date.valueOf(today().plusMonths(1)), studioId);
            }
        }
        if (planPaidUntil != null) {
            jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?", Date.valueOf(planPaidUntil), studioId);
        }
        if (monthlyQuota != null) {
            if (monthlyQuota < 0 || monthlyQuota > 1000) {
                throw ApiException.badRequest("INVALID_QUOTA", "The monthly allowance must be between 0 and 1000");
            }
            jdbc.update("UPDATE admin_users SET monthly_quota = ? WHERE id = ?", monthlyQuota, studioId);
        }
        if (addCredits != null && addCredits != 0) {
            jdbc.update("UPDATE admin_users SET main_credits = main_credits + ? WHERE id = ?", addCredits, studioId);
        }
        if (addQuickCredits != null && addQuickCredits != 0) {
            jdbc.update("UPDATE admin_users SET quick_credits = quick_credits + ? WHERE id = ?", addQuickCredits, studioId);
        }
        if (addClientCredits != null && addClientCredits != 0) {
            jdbc.update("UPDATE admin_users SET client_credits = client_credits + ? WHERE id = ?", addClientCredits, studioId);
        }
        return studios().stream().filter(s -> s.studioId().equals(studioId)).findFirst().orElseThrow();
    }

    /** A pack due for renewal within {@code days} days, not yet reminded for this period. */
    public record RenewalDue(UUID studioId, String email, String name, Plan plan, LocalDate paidUntil, int price) {
    }

    public List<RenewalDue> renewalsDue(int days) {
        Settings s = settings();
        LocalDate today = today();
        return jdbc.query("""
                SELECT id, email, display_name, plan, plan_paid_until FROM admin_users
                WHERE plan IN ('MAIN_2', 'MAIN_4', 'QUICK_2', 'QUICK_4') AND NOT disabled
                  AND plan_paid_until BETWEEN ? AND ? AND plan_reminded_for IS DISTINCT FROM plan_paid_until""",
                (rs, n) -> {
                    Plan plan = Plan.valueOf(rs.getString(4));
                    return new RenewalDue(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), plan,
                            rs.getDate(5).toLocalDate(), s.packPrice(plan));
                }, Date.valueOf(today), Date.valueOf(today.plusDays(days)));
    }

    public void markReminded(UUID studioId, LocalDate paidUntil) {
        jdbc.update("UPDATE admin_users SET plan_reminded_for = ? WHERE id = ?", Date.valueOf(paidUntil), studioId);
    }
}
