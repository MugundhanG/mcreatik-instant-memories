package com.mcreatik.gallery.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;

/** Creates the first admin from ADMIN_EMAIL / ADMIN_PASSWORD when the admin table is empty. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final GalleryProperties properties;

    public AdminBootstrap(AdminUserRepository users, PasswordEncoder passwordEncoder, GalleryProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.security().bootstrapAdminEmail();
        String password = properties.security().bootstrapAdminPassword();
        if (users.count() > 0 || email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (password.length() < 10) {
            log.warn("ADMIN_PASSWORD must be at least 10 characters; bootstrap admin not created");
            return;
        }
        users.save(new AdminUser(email, passwordEncoder.encode(password), "McreatiK Admin"));
        log.info("Bootstrap admin created for {}", email);
    }
}
