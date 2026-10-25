package com.example.cx.core.tickets;

import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.ErrorCode;
import com.example.cx.core.identity.OrganizationRepository;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.TicketDtos.CreateTicketRequest;
import com.example.cx.core.tickets.TicketDtos.TicketDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent ticket creation.
 *
 * Contract: {@code Idempotency-Key} is scoped to (tenant, user). Same key + same request body -> the ORIGINAL response is replayed
 * (no second ticket). Same key + different body -> 409 DUPLICATE_SUBMISSION. The key row is written in the same transaction as the
 * ticket, so a failed creation never "burns" a key. Two concurrent requests with one key race on the unique constraint; the loser
 * rolls back completely and then replays the winner response.
 */
@Service
public class TicketCommandService {
    private static final Logger log = LoggerFactory.getLogger(TicketCommandService.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    public record Result(TicketDto ticket, boolean replayed) {}

    private final TicketService tickets;
    private final IdempotencyKeyRepository keys;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final OrganizationRepository orgs;
    private final TicketNumberAllocator numbers;

    public TicketCommandService(TicketService tickets, IdempotencyKeyRepository keys, ObjectMapper mapper, PlatformTransactionManager txm,
                                OrganizationRepository orgs, TicketNumberAllocator numbers) {
        this.orgs = orgs;
        this.numbers = numbers;
        this.tickets = tickets;
        this.keys = keys;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
    }

    public Result create(String idempotencyKey, CreateTicketRequest request) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches())
            throw ApiException.validation("Idempotency-Key", "required: 1-100 chars of letters, digits, . _ : -");
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        String hash = hash(request);

        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<IdempotencyKey> existing = keys.findByTenantIdAndUserIdAndKey(tenant, me.userId(), idempotencyKey);
            if (existing.isPresent()) return replay(existing.get(), hash);
            try {
                // allocated in its own tiny transaction BEFORE the business transaction (no connection is held here), see TicketNumberAllocator
                long number = numbers.next(tenant);
                TicketDto created = tx.execute(status -> {
                    TicketDto dto = tickets.create(request, number);
                    IdempotencyKey k = new IdempotencyKey();
                    k.setId(UUID.randomUUID());
                    k.setTenantId(tenant);
                    k.setUserId(me.userId());
                    k.setKey(idempotencyKey);
                    k.setRequestHash(hash);
                    k.setResponseStatus(201);
                    k.setResponseBody(json(dto));
                    k.setCreatedAt(Instant.now());
                    keys.saveAndFlush(k); // unique violation surfaces here, rolling back the ticket too
                    return dto;
                });
                return new Result(created, false);
            } catch (DataIntegrityViolationException e) {
                log.debug("Idempotency race on key {}; replaying the winner", idempotencyKey);
            }
        }
        throw ApiException.conflict("Could not complete idempotent creation; retry");
    }

    private Result replay(IdempotencyKey k, String hash) {
        if (!k.getRequestHash().equals(hash))
            throw new ApiException(ErrorCode.DUPLICATE_SUBMISSION, "Idempotency-Key was already used with a different request body");
        try {
            return new Result(mapper.readValue(k.getResponseBody(), TicketDto.class), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    /** Housekeeping: keys older than 24h are no longer replayable. Runs per tenant so it also works under PostgreSQL RLS. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purgeOldKeys() {
        try {
            Instant cutoff = Instant.now().minusSeconds(86_400);
            int page = 0;
            org.springframework.data.domain.Page<com.example.cx.core.identity.Organization> p;
            do {
                p = orgs.findAllByOrderBySlugAsc(org.springframework.data.domain.PageRequest.of(page++, 100));
                for (var o : p.getContent())
                    TenantContext.runAs(o.getId(), () -> tx.executeWithoutResult(s -> keys.deleteOlderThan(cutoff)));
            } while (p.hasNext());
        } catch (RuntimeException e) {
            log.warn("Idempotency purge failed: {}", e.toString());
        }
    }

    private String hash(CreateTicketRequest r) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(r).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(TicketDto dto) {
        try {
            return mapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
