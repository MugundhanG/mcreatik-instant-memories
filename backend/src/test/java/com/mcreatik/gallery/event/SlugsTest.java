package com.mcreatik.gallery.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    void newGalleryAddressesEndInTenRandomCharacters() {
        String slug = Slugs.generate("Arun & Priya Wedding");
        assertThat(slug).matches("arun-priya-wedding-[a-z2-9]{10}");
        assertThat(Slugs.isValid(slug)).isTrue();
        assertThat(Slugs.generate("Arun & Priya Wedding")).isNotEqualTo(slug);
        // Long names are shortened so the address stays valid.
        assertThat(Slugs.isValid(Slugs.generate("x".repeat(200)))).isTrue();
    }
}
