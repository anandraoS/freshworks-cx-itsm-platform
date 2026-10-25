package com.example.cx.core.security;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :now")
    int deleteExpired(Instant now);
}
