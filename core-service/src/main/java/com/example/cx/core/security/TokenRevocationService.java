package com.example.cx.core.security;

import com.example.cx.core.config.CxProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revocation list backed by the DB (so it is shared by all instances) with two small caches:
 * <ul>
 *   <li><b>positive</b> (revoked, 10 min): once revoked a token stays revoked, so this can be long-lived;</li>
 *   <li><b>negative</b> (known NOT revoked, default 3 s, {@code cx.security.revocation-negative-cache-seconds}, 0 disables):
 *       removes the per-request {@code revoked_token} lookup on the hot path (every authenticated request used to run one query).</li>
 * </ul>
 * Safety of the negative cache: the positive cache is always consulted first, and {@link #revoke} writes the positive entry and
 * drops the negative one, so on the node that processes the logout the token is rejected immediately. On OTHER nodes a token
 * that was cached as "not revoked" stays usable for at most the negative TTL (default 3 s) after logout; set the property to 0 to
 * get strict "revoked everywhere on the next request" semantics at the cost of one query per request.
 */
@Service
public class TokenRevocationService {
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);
    private final RevokedTokenRepository repo;
    private final Cache<String, Boolean> revokedCache =
            Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, Boolean> notRevokedCache;

    @Autowired
    public TokenRevocationService(RevokedTokenRepository repo, CxProperties props) {
        this(repo, Duration.ofSeconds(Math.max(0, props.security() == null ? 3 : props.security().revocationNegativeCacheSeconds())), Ticker.systemTicker());
    }

    /** Test seam: explicit negative TTL and clock. */
    TokenRevocationService(RevokedTokenRepository repo, Duration negativeTtl, Ticker ticker) {
        this.repo = repo;
        this.notRevokedCache = negativeTtl.isZero() ? null
                : Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(negativeTtl).ticker(ticker).build();
    }

    @Transactional
    public void revoke(String jti, Instant tokenExpiry) {
        if (!repo.existsById(jti)) repo.save(new RevokedToken(jti, tokenExpiry));
        revokedCache.put(jti, Boolean.TRUE);                       // positive first: it is consulted before the negative cache
        if (notRevokedCache != null) notRevokedCache.invalidate(jti);
    }

    // no @Transactional: a cache hit must not open a transaction / borrow a connection; existsById is its own read-only tx
    public boolean isRevoked(String jti) {
        if (revokedCache.getIfPresent(jti) != null) return true;
        if (notRevokedCache != null && notRevokedCache.getIfPresent(jti) != null) return false;
        boolean r = repo.existsById(jti);
        if (r) revokedCache.put(jti, Boolean.TRUE);
        else if (notRevokedCache != null) notRevokedCache.put(jti, Boolean.TRUE);
        return r;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void purgeExpired() {
        int n = repo.deleteExpired(Instant.now());
        if (n > 0) log.info("Purged {} expired revoked-token rows", n);
    }
}
