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

@RestController
@RequestMapping("/api/admin/billing")
public class BillingController {

    private final BillingService billing;
    private final AdminAccess access;
    private final OnlinePaymentService online;

    public BillingController(BillingService billing, AdminAccess access, OnlinePaymentService online) {
        this.billing = billing;
        this.access = access;
        this.online = online;
    }

    /**
     * What to buy. {@code item} defaults to Main events (the older API sent only {@code events}); {@code quantity}
     * (or {@code events}) is the number of events or months; {@code eventId} for an upgrade; {@code plan} for a pack.
     */
    public record OnlineOrderRequest(Integer events, BillingService.Item item, Integer quantity, UUID eventId,
                                     BillingService.Plan plan) {
        BillingService.Purchase purchase() {
            return BillingService.normalize(item, quantity != null ? quantity : events, eventId, plan);
        }
    }

    /** Pay online (card, netbanking, UPI, wallet): opens a Razorpay order at the server's price. */
    @PostMapping("/online-orders")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OnlinePaymentService.CheckoutOrder startOnline(Authentication auth, @RequestBody OnlineOrderRequest request) {
        return online.start(CurrentAdmin.id(auth), request.purchase());
    }

    public record ConfirmRequest(String razorpayPaymentId, String razorpaySignature) {
    }

    /** Back from Razorpay Checkout: verified with Razorpay, then the events are added (once). */
    @PostMapping("/online-orders/{paymentId}/confirm")
    public OnlinePaymentService.Result confirmOnline(Authentication auth, @PathVariable UUID paymentId,
                                                     @RequestBody ConfirmRequest request) {
        return online.confirm(CurrentAdmin.id(auth), paymentId, request.razorpayPaymentId(), request.razorpaySignature());
    }

    /** UPI QR confirmed automatically: a single-use Razorpay QR for the exact amount. */
    @PostMapping("/upi-qr")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OnlinePaymentService.UpiQrOrder startUpiQr(Authentication auth, @RequestBody OnlineOrderRequest request) {
        return online.startQr(CurrentAdmin.id(auth), request.purchase());
    }

    /** "Paid yet?" for a UPI QR (checks with Razorpay; adds the events once paid). */
    @GetMapping("/online-orders/{paymentId}")
    public OnlinePaymentService.Status onlineStatus(Authentication auth, @PathVariable UUID paymentId) {
        return online.status(CurrentAdmin.id(auth), paymentId);
    }

    /** The studio closed the QR: stop it accepting payments. */
    @org.springframework.web.bind.annotation.DeleteMapping("/online-orders/{paymentId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancelOnline(Authentication auth, @PathVariable UUID paymentId) {
        online.cancel(CurrentAdmin.id(auth), paymentId);
    }

    /** The logged-in studio's plan, this month's usage, bought events, UPI details and its payments. */
    @GetMapping
    public BillingService.Summary summary(Authentication auth) {
        return billing.summary(CurrentAdmin.id(auth));
    }

    /** A UPI QR payment the studio made, with its transaction ID; the other fields as in {@link OnlineOrderRequest}. */
    public record PaymentRequest(Integer events, String utr, BillingService.Item item, Integer quantity, UUID eventId,
                                 BillingService.Plan plan) {
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.Payment pay(Authentication auth, @RequestBody PaymentRequest request) {
        return billing.submitPayment(CurrentAdmin.id(auth), BillingService.normalize(request.item(),
                request.quantity() != null ? request.quantity() : request.events(), request.eventId(), request.plan()),
                request.utr());
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
