package com.mcreatik.gallery.billing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Razorpay tells us about payments here (so a studio who closed the tab after paying still gets its events). */
@RestController
@RequestMapping("/api/public/razorpay")
public class RazorpayWebhookController {

    private final OnlinePaymentService payments;
    private final RazorpayClient razorpay;
    private final RazorpayProperties props;

    public RazorpayWebhookController(OnlinePaymentService payments, RazorpayClient razorpay, RazorpayProperties props) {
        this.payments = payments;
        this.razorpay = razorpay;
        this.props = props;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!props.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!razorpay.webhookSignatureValid(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        payments.webhook(body);
        return ResponseEntity.ok().build();
    }
}
