package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Negative ("not revoked") cache of the per-request revoked_token lookup: fewer queries, never a revoked token let through on the revoking node. */
class TokenRevocationCacheTest {
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private RevokedTokenRepository repo;

    @BeforeEach
    void setUp() { repo = mock(RevokedTokenRepository.class); }

    private TokenRevocationService service(Duration ttl) { return new TokenRevocationService(repo, ttl, ticker); }

    @Test
    void repeatedChecksOfAValidTokenHitTheDatabaseOncePerTtl() {
        TokenRevocationService s = service(Duration.ofSeconds(3));
        when(repo.existsById("j1")).thenReturn(false);
        for (int i = 0; i < 50; i++) assertThat(s.isRevoked("j1")).isFalse();
        verify(repo, times(1)).existsById("j1");

        nanos.addAndGet(Duration.ofSeconds(4).toNanos());   // TTL elapsed -> re-checked
        assertThat(s.isRevoked("j1")).isFalse();
        verify(repo, times(2)).existsById("j1");
    }

    @Test
    void logoutOnThisNodeTakesEffectImmediatelyEvenWithAFreshNegativeEntry() {
        TokenRevocationService s = service(Duration.ofMinutes(10));   // huge TTL: only explicit invalidation can save us
        when(repo.existsById("j2")).thenReturn(false);
        assertThat(s.isRevoked("j2")).isFalse();                       // negative entry cached

        s.revoke("j2", Instant.now().plusSeconds(60));
        assertThat(s.isRevoked("j2")).isTrue();                        // immediate
        verify(repo, times(2)).existsById("j2");                       // 1 x first check + 1 x inside revoke(); the last isRevoked used the cache
    }

    @Test
    void revokedTokensAreNeverCachedAsValid() {
        TokenRevocationService s = service(Duration.ofSeconds(3));
        when(repo.existsById("j3")).thenReturn(true);                  // revoked by another node
        assertThat(s.isRevoked("j3")).isTrue();
        assertThat(s.isRevoked("j3")).isTrue();
        verify(repo, times(1)).existsById("j3");                       // positive cache
    }

    @Test
    void otherNodeRevocationIsVisibleAfterAtMostTheNegativeTtl() {
        TokenRevocationService s = service(Duration.ofSeconds(3));
        when(repo.existsById("j4")).thenReturn(false);
        assertThat(s.isRevoked("j4")).isFalse();
        when(repo.existsById("j4")).thenReturn(true);                  // another node logs the user out
        assertThat(s.isRevoked("j4")).isFalse();                       // documented window: still within TTL
        nanos.addAndGet(Duration.ofSeconds(3).toNanos() + 1);
        assertThat(s.isRevoked("j4")).isTrue();                        // bounded by the TTL
    }

    @Test
    void ttlZeroDisablesTheNegativeCache() {
        TokenRevocationService s = service(Duration.ZERO);
        when(repo.existsById("j5")).thenReturn(false);
        s.isRevoked("j5");
        s.isRevoked("j5");
        verify(repo, times(2)).existsById("j5");
        verify(repo, never()).save(any());
    }
}
