package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignInValidationTest {

    @Test
    void emailIsRequiredAndTrimmed() {
        assertThat(SignInValidation.emailError("")).isEqualTo("Enter your email");
        assertThat(SignInValidation.emailError("   ")).isEqualTo("Enter your email");
        assertThat(SignInValidation.emailError(null)).isEqualTo("Enter your email");
        assertThat(SignInValidation.normalizeEmail("  hello@lenslight.test ")).isEqualTo("hello@lenslight.test");
        assertThat(SignInValidation.emailError("  hello@lenslight.test ")).isNull();
    }

    @Test
    void emailNeedsNameDomainAndTld() {
        for (String ok : new String[] {"a@b.co", "hello@lenslight.test", "first.last+tag@mail.studio.com", "o'neil@x.in"}) {
            assertThat(SignInValidation.emailError(ok)).as(ok).isNull();
        }
        for (String bad : new String[] {"hello", "hello@", "@studio.com", "hello@studio", "hello@studio.", "a b@studio.com",
                "hello@-studio.com", "hello@studio..com", "hello@studio.c", "a@@b.com"}) {
            assertThat(SignInValidation.emailError(bad)).as(bad)
                    .isEqualTo("Enter a valid email, e.g. name@studio.com");
        }
    }

    @Test
    void emailLengthIsCappedAt255() {
        String domain = "@studio.com";
        String max = "a".repeat(255 - domain.length()) + domain;
        assertThat(max).hasSize(255);
        assertThat(SignInValidation.emailError(max)).isNull();
        assertThat(SignInValidation.emailError("a" + max)).isNotNull();
    }

    @Test
    void passwordIsRequiredNeverTrimmedAndCappedAt200() {
        assertThat(SignInValidation.passwordError("")).isEqualTo("Enter your password");
        assertThat(SignInValidation.passwordError(null)).isEqualTo("Enter your password");
        assertThat(SignInValidation.passwordError(" ")).isNull(); // spaces count: it is not trimmed
        assertThat(SignInValidation.passwordError("x".repeat(200))).isNull();
        assertThat(SignInValidation.passwordError("x".repeat(201))).contains("200");
    }

    @Test
    void signInIsAllowedOnlyWhenBothAreValid() {
        assertThat(SignInValidation.canSubmit("hello@lenslight.test", "secret")).isTrue();
        assertThat(SignInValidation.canSubmit("hello@lenslight.test", "")).isFalse();
        assertThat(SignInValidation.canSubmit("nope", "secret")).isFalse();
        assertThat(SignInValidation.canSubmit("", "")).isFalse();
    }
}
