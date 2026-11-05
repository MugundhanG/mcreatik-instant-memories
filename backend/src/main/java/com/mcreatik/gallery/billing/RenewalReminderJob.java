package com.mcreatik.gallery.billing;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.mail.Mailer;

/**
 * Monthly packs are renewed by hand (UPI) until auto-pay: three days before a pack's paid-until date the studio gets
 * one email (the dashboard also shows a banner). One email per period, even if the job runs again.
 */
@Component
public class RenewalReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RenewalReminderJob.class);
    static final int DAYS_BEFORE = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final BillingService billing;
    private final Mailer mailer;
    private final GalleryProperties properties;

    public RenewalReminderJob(BillingService billing, Mailer mailer, GalleryProperties properties) {
        this.billing = billing;
        this.mailer = mailer;
        this.properties = properties;
    }

    /** 10:00 in India. */
    @Scheduled(cron = "0 30 4 * * *", zone = "UTC")
    public void remind() {
        for (BillingService.RenewalDue due : billing.renewalsDue(DAYS_BEFORE)) {
            String body = """
                    Hello %s,

                    Your %s pack on McreatiK Instant Memories is paid until %s.

                    To keep your monthly events, renew it for ₹%,d: open your dashboard, then Events → Renew pack, and pay by UPI:
                    %s/admin

                    If you don't renew, your gallery and events stay as they are: new events are simply charged one by one.

                    McreatiK Instant Memories
                    """.formatted(due.name(), due.plan().label(), DAY.format(due.paidUntil()), due.price(), properties.publicGalleryBaseUrl().replaceAll("/+$", ""));
            if (mailer.send(due.email(), "Your McreatiK pack renews on " + DAY.format(due.paidUntil()), body)) {
                billing.markReminded(due.studioId(), due.paidUntil());
                log.info("Renewal reminder sent to studio {}", due.studioId());
            }
        }
    }
}
