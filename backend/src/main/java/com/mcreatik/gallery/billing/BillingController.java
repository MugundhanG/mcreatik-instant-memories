package com.mcreatik.gallery.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.AdminAccess;
import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.mail.Mailer;

@RestController
@RequestMapping("/api/admin/billing")
public class BillingController {

    private final BillingService billing;
    private final AdminAccess access;
    private final Mailer mailer;
    private final GalleryProperties properties;

    public BillingController(BillingService billing, AdminAccess access, Mailer mailer, GalleryProperties properties) {
        this.billing = billing;
        this.access = access;
        this.mailer = mailer;
        this.properties = properties;
    }

    /** The logged-in studio's plan, this month's usage, bought events, UPI details and its payments. */
    @GetMapping
    public BillingService.Summary summary(Authentication auth) {
        return billing.summary(CurrentAdmin.id(auth));
    }

    /**
     * A UPI QR payment the studio made, with its transaction ID. {@code item} defaults to Main events (the older API
     * sent only {@code events}); {@code quantity} (or {@code events}) is the number of events, months or GB;
     * {@code eventId} for an upgrade, storage or more months; {@code plan} for a pack; {@code months} for a client
     * gallery's open months (CLIENT_SETUP).
     */
    public record PaymentRequest(Integer events, String utr, BillingService.Item item, Integer quantity, UUID eventId,
                                 BillingService.Plan plan, Integer storageGb, Integer months) {
    }

    /** What a purchase would cost, for the dashboard's price breakdown (nothing is bought). */
    @PostMapping("/quote")
    public BillingService.Quote quote(Authentication auth, @RequestBody PaymentRequest request) {
        return billing.quote(CurrentAdmin.id(auth), BillingService.normalize(request.item(),
                request.quantity() != null ? request.quantity() : request.events(), request.eventId(), request.plan(),
                request.storageGb(), request.months()));
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.Payment pay(Authentication auth, @RequestBody PaymentRequest request) {
        BillingService.Payment p = billing.submitPayment(CurrentAdmin.id(auth), BillingService.normalize(request.item(),
                request.quantity() != null ? request.quantity() : request.events(), request.eventId(), request.plan(),
                request.storageGb(), request.months()),
                request.utr());
        if (!p.applied()) {
            notifyHeld(p);
        }
        return p;
    }

    /** A new studio's payment waits for McreatiK: tell McreatiK so the studio is not kept waiting. */
    private void notifyHeld(BillingService.Payment p) {
        String to = properties.security().bootstrapAdminEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        String body = """
                %s paid ₹%,d by UPI and is waiting for you to confirm it (their first payment, so nothing is added yet).

                UPI transaction ID: %s

                Check it in the bank statement, then approve or reject it in the dashboard: Pricing & payments, Payments to check.
                """.formatted(p.studioName(), p.amount(), p.utr());
        Thread.ofVirtual().name("payment-held-mail").start(() ->
                mailer.send(to, "Payment to confirm: " + p.studioName() + ", ₹" + String.format("%,d", p.amount()), body));
    }

    // ---- McreatiK (super admin) ----

    @GetMapping("/payments")
    public List<BillingService.Payment> payments(Authentication auth, @RequestParam(required = false) String status) {
        access.requireSuperAdmin(auth);
        return billing.payments(status);
    }

    @PostMapping("/payments/{paymentId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth, @PathVariable UUID paymentId) {
        access.requireSuperAdmin(auth);
        billing.approve(paymentId);
    }

    public record RejectRequest(String note) {
    }

    @PostMapping("/payments/{paymentId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(Authentication auth, @PathVariable UUID paymentId, @RequestBody(required = false) RejectRequest request) {
        access.requireSuperAdmin(auth);
        billing.reject(paymentId, request == null ? null : request.note());
    }

    @GetMapping("/studios")
    public List<BillingService.StudioBilling> studios(Authentication auth) {
        access.requireSuperAdmin(auth);
        return billing.studios();
    }

    /** {@code addCredits}: Main events to give (minus to take back); {@code addQuickCredits}: Quick events. */
    public record StudioBillingRequest(BillingService.Plan plan, Integer monthlyQuota, Integer addCredits,
                                       Integer addQuickCredits, java.time.LocalDate planPaidUntil, Integer addClientCredits) {
    }

    @PatchMapping("/studios/{studioId}")
    public BillingService.StudioBilling updateStudio(Authentication auth, @PathVariable UUID studioId,
                                                     @RequestBody StudioBillingRequest request) {
        access.requireSuperAdmin(auth);
        return billing.updateStudio(studioId, request.plan(), request.monthlyQuota(), request.addCredits(),
                request.addQuickCredits(), request.planPaidUntil(), request.addClientCredits());
    }

    @GetMapping("/settings")
    public BillingService.Settings settings(Authentication auth) {
        access.requireSuperAdmin(auth);
        return billing.settings();
    }

    @PutMapping("/settings")
    public BillingService.Settings updateSettings(Authentication auth, @RequestBody BillingService.Settings request) {
        access.requireSuperAdmin(auth);
        return billing.updateSettings(request);
    }
}
