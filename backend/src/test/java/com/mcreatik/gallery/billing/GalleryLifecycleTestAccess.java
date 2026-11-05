package com.mcreatik.gallery.billing;

import org.springframework.stereotype.Component;

/** Lets tests run one step of the daily gallery job (its methods are package-private). */
@Component
public class GalleryLifecycleTestAccess {

    private final GalleryLifecycleJob job;

    public GalleryLifecycleTestAccess(GalleryLifecycleJob job) {
        this.job = job;
    }

    public void lockUnpaidStorage() {
        job.lockUnpaidStorage(BillingService.today());
    }

    public void warnBeforeExpiry() {
        job.warnBeforeExpiry(BillingService.today());
    }

    public void noticeExpired() {
        job.noticeExpired(BillingService.today());
    }
}
