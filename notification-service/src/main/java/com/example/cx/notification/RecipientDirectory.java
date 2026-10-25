package com.example.cx.notification;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Answers "which of these user ids really belong to this tenant?". The broker (or the in-process dispatcher) is trusted
 * to deliver authentic events, but a bug or a forged message must not be able to put a notification into another
 * tenant's user inbox, so recipients are checked against the user directory when it is reachable.
 */
public interface RecipientDirectory {
    /**
     * @return the subset of {@code userIds} that exist in {@code tenantId}, or empty when the directory cannot answer
     *         (the user table is not reachable from this deployment); callers then fall back to the payload check only.
     */
    Optional<Set<UUID>> usersInTenant(UUID tenantId, Collection<UUID> userIds);
}
