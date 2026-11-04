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

    public record OnlineOrderRequest(int events) {
    }

    /** Pay online (card, netbanking, UPI, wallet): opens a Razorpay order at the server's price. */
    @PostMapping("/online-orders")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OnlinePaymentService.CheckoutOrder startOnline(Authentication auth, @RequestBody OnlineOrderRequest request) {
        return online.start(CurrentAdmin.id(auth), request.events());
    }

    public record ConfirmRequest(String razorpayPaymentId, String razorpaySignature) {
    }

    /** Back from Razorpay Checkout: verified with Razorpay, then the events are added (once). */
    @PostMapping("/online-orders/{paymentId}/confirm")
    public OnlinePaymentService.Result confirmOnline(Authentication auth, @PathVariable UUID paymentId,
                                                     @RequestBody ConfirmRequest request) {
        return online.confirm(CurrentAdmin.id(auth), paymentId, request.razorpayPaymentId(), request.razorpaySignature());
    }

    /** The logged-in studio's plan, this month's usage, bought events, UPI details and its payments. */
    @GetMapping
    public BillingService.Summary summary(Authentication auth) {
        return billing.summary(CurrentAdmin.id(auth));
    }

    public record PaymentRequest(int events, String utr) {
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.Payment pay(Authentication auth, @RequestBody PaymentRequest request) {
        return billing.submitPayment(CurrentAdmin.id(auth), request.events(), request.utr());
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

    public record StudioBillingRequest(BillingService.Plan plan, Integer monthlyQuota, Integer addCredits) {
    }

    @PatchMapping("/studios/{studioId}")
    public BillingService.StudioBilling updateStudio(Authentication auth, @PathVariable UUID studioId,
                                                     @RequestBody StudioBillingRequest request) {
        access.requireSuperAdmin(auth);
        return billing.updateStudio(studioId, request.plan(), request.monthlyQuota(), request.addCredits());
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
