package com.mcreatik.gallery.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailEnvironmentTest {

    @Test
    void emptyPrefixLeavesSubjectsUnchanged() {
        assertThat(SmtpMailer.withPrefix("", "Your McreatiK temporary password")).isEqualTo("Your McreatiK temporary password");
        assertThat(SmtpMailer.withPrefix(null, "Hello")).isEqualTo("Hello");
        assertThat(SmtpMailer.withPrefix("   ", "Hello")).isEqualTo("Hello");
    }

    @Test
    void betaPrefixIsPrepended() {
        assertThat(SmtpMailer.withPrefix("[BETA] ", "Your McreatiK temporary password")).isEqualTo("[BETA] Your McreatiK temporary password");
    }

    @Test
    void prefixWithoutTrailingSpaceGetsOne() {
        assertThat(SmtpMailer.withPrefix("[BETA]", "Hello")).isEqualTo("[BETA] Hello");
    }

    @Test
    void logoUrlIsUnchangedForProduction() {
        assertThat(MailSignature.logoUrl("https://live.mcreatik.com")).isEqualTo("https://live.mcreatik.com/email/mk-logo.png");
        assertThat(MailSignature.logoUrl("https://live.mcreatik.com/")).isEqualTo(MailSignature.LOGO_URL);
        assertThat(MailSignature.LOGO_URL).isEqualTo("https://live.mcreatik.com/email/mk-logo.png");
    }

    @Test
    void logoUrlFollowsTheConfiguredSite() {
        assertThat(MailSignature.logoUrl("https://beta.live.mcreatik.com")).isEqualTo("https://beta.live.mcreatik.com/email/mk-logo.png");
    }

    @Test
    void logoUrlFallsBackToProductionWhenBaseUrlIsUnusable() {
        assertThat(MailSignature.logoUrl(null)).isEqualTo(MailSignature.LOGO_URL);
        assertThat(MailSignature.logoUrl("")).isEqualTo(MailSignature.LOGO_URL);
        assertThat(MailSignature.logoUrl("http://localhost:5173")).isEqualTo(MailSignature.LOGO_URL);
        assertThat(MailSignature.logoUrl("not a url")).isEqualTo(MailSignature.LOGO_URL);
    }

    @Test
    void htmlUsesTheGivenLogo() {
        assertThat(MailSignature.html("Hi")).contains("src=\"https://live.mcreatik.com/email/mk-logo.png\"");
        assertThat(MailSignature.html("Hi", MailSignature.logoUrl("https://beta.live.mcreatik.com")))
                .contains("src=\"https://beta.live.mcreatik.com/email/mk-logo.png\"");
    }
}
