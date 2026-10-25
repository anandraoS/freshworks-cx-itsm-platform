package com.example.cx.core.tickets;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/** Stores the original response so a retry with the same Idempotency-Key replays it instead of creating a duplicate. */
@Entity
@Table(name = "idempotency_key")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "idem_key", nullable = false, length = 100)
    private String key;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "response_status", nullable = false)
    private int responseStatus;
    @Column(name = "response_body", nullable = false, length = 20000)
    private String responseBody;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
