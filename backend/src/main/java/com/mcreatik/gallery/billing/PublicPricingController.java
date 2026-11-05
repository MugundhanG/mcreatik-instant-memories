package com.mcreatik.gallery.billing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current prices for the public /pricing page (no login), so the page never goes out of date. */
@RestController
public class PublicPricingController {

    /** Events in the Monthly plan unless McreatiK changes a studio's quota. */
    static final int MONTHLY_EVENTS = 4;

    /**
     * {@code perEventPrice} / {@code monthlyPrice} / {@code monthlyEvents} / {@code extraEventPrice}: the older single
     * plan (kept for old pages); {@code packages}: Main and Quick prices and the monthly packs.
     */
    public record Pricing(int perEventPrice, int monthlyPrice, int monthlyEvents, int extraEventPrice, String currency,
                          BillingService.Prices packages) {
    }

    private final BillingService billing;

    public PublicPricingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/api/public/pricing")
    public Pricing pricing() {
        BillingService.Settings s = billing.settings();
        return new Pricing(s.perEventPrice(), s.monthlyPrice(), MONTHLY_EVENTS, s.extraEventPrice(), "INR",
                billing.publicPrices());
    }
}
