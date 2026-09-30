package com.mcreatik.gallery.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    @Query("select u from AdminUser u where lower(u.email) = lower(?1)")
    Optional<AdminUser> findByEmail(String email);
}
