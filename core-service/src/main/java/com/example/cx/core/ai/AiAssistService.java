package com.example.cx.core.ai;

import com.example.cx.core.ai.AiDtos.ArticleRef;
import com.example.cx.core.ai.AiDtos.CategoryRef;
import com.example.cx.core.ai.AiDtos.SuggestionDto;
import com.example.cx.core.ai.AiProvider.AiRequest;
import com.example.cx.core.ai.AiProvider.AiResult;
import com.example.cx.core.ai.AiProvider.ArticleOption;
import com.example.cx.core.ai.AiProvider.CategoryOption;
import com.example.cx.core.ai.AiProvider.ScoredArticle;
import com.example.cx.core.audit.AuditService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.RateLimitedException;
import com.example.cx.core.docstore.DocumentStore;
import com.example.cx.core.kb.KbCandidate;
import com.example.cx.core.kb.KbService;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketCategory;
import com.example.cx.core.tickets.TicketCategoryRepository;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates AI-assist suggestions. SAFETY MODEL (details in docs/ai-safety-notes.md):
 *  - ticket text is DATA: sanitised (instruction-like sentences dropped, PII/secrets redacted, length bounded) before any provider sees it;
 *  - the provider is pure (no DB / tenant / action access); its output is validated against the candidate lists and cleaned;
 *  - the result is stored as an inert PENDING_REVIEW suggestion: nothing is posted, assigned, re-prioritised or sent by this class;
 *  - failure never surfaces as an error: timeout / provider error / open circuit / overload => HTTP 200 degraded:true + fallbackMessage;
 *  - resilience: per-call timeout, bounded retries (max {@code cx.ai.max-retries}, hard cap 3) inside a total deadline,
 *    per-tenant rate limit (429), circuit-breaker-lite.
 * The provider call runs OUTSIDE any database transaction (a slow provider must never hold a connection).
 */
@Service
public class AiAssistService {
    private static final Logger log = LoggerFactory.getLogger(AiAssistService.class);
    static final String META = "ai_suggestion_meta";
    static final String FALLBACK = "AI assistance is temporarily unavailable. You can continue working on the ticket manually.";
    private static final TypeReference<List<ArticleRef>> ARTICLE_LIST = new TypeReference<>() {};

    private record Context(Ticket ticket, List<TicketCategory> categories, List<KbCandidate> articles) {}

    private record Outcome(AiResult result, String failureReason, int attempts, long latencyMs) {
        boolean ok() { return result != null; }
    }

    private final TicketRepository tickets;
    private final TicketCategoryRepository categories;
    private final KbService kb;
    private final AiSuggestionRepository repo;
    private final AiProvider provider;
    private final AiSettings settings;
    private final AiFaultInjector faults;
    private final AiCircuitBreaker breaker;
    private final AiRateLimiter limiter;
    private final DocumentStore docs;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final ThreadPoolExecutor pool;

    public AiAssistService(TicketRepository tickets, TicketCategoryRepository categories, KbService kb, AiSuggestionRepository repo,
                           AiProvider provider, AiSettings settings, AiFaultInjector faults, AiCircuitBreaker breaker,
                           AiRateLimiter limiter, DocumentStore docs, AuditService audit, MeterRegistry meters, ObjectMapper mapper,
                           PlatformTransactionManager txm) {
        this.tickets = tickets;
        this.categories = categories;
        this.kb = kb;
        this.repo = repo;
        this.provider = provider;
        this.settings = settings;
        this.faults = faults;
        this.breaker = breaker;
        this.limiter = limiter;
        this.docs = docs;
        this.audit = audit;
        this.meters = meters;
        this.mapper = mapper;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txm);
        AtomicInteger n = new AtomicInteger();
        // bounded: 4 workers + 16 queued; anything beyond is rejected => degraded (never an unbounded backlog)
        this.pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "ai-provider-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }

    // ---- analyze ------------------------------------------------------------------------------------------------------

    public SuggestionDto analyze(UUID ticketId) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Context ctx = readTx.execute(s -> load(tenant, ticketId));            // 404 for unknown / other-tenant tickets
        try {
            limiter.check(tenant);
        } catch (RateLimitedException e) {
            meters.counter("cx.ai.analyze", "result", "rate_limited").increment();
            throw e;
        }

        Ticket t = ctx.ticket();
        AiSanitizer.Result title = AiSanitizer.sanitize(t.getTitle(), 200);
        AiSanitizer.Result desc = AiSanitizer.sanitize(t.getDescription(), 4000);
        Map<String, Integer> redactions = new LinkedHashMap<>(title.redactions());
        desc.redactions().forEach((k, v) -> redactions.merge(k, v, Integer::sum));
        int signals = title.injectionSignals() + desc.injectionSignals();
        String currentCategory = ctx.categories().stream().filter(c -> c.getId().equals(t.getCategoryId())).map(TicketCategory::getName).findFirst().orElse(null);

        List<ArticleOption> options = ctx.articles().stream()
                .map(a -> new ArticleOption(a.id(), AiSanitizer.cleanOutput(a.title(), 200), AiSanitizer.cleanOutput(a.summary(), 500), a.visibility() == Visibility.PUBLIC)).toList();
        AiRequest req = new AiRequest(title.text(), desc.text(), currentCategory,
                ctx.categories().stream().map(c -> new CategoryOption(c.getId(), c.getName())).toList(), options);
        String promptHash = sha256(title.text() + "\n" + desc.text());

        Outcome out = callProvider(req);
        if (!out.ok()) {
            meters.counter("cx.ai.analyze", "result", "degraded").increment();
            writeTx.executeWithoutResult(s -> audit.record("AI_ANALYSIS_DEGRADED", "TICKET", ticketId,
                    Map.of("provider", provider.name(), "reason", out.failureReason(), "attempts", out.attempts())));
            return degraded();
        }
        meters.counter("cx.ai.analyze", "result", "ok").increment();
        SuggestionDto dto = writeTx.execute(s -> persist(tenant, me, ticketId, ctx, out, promptHash, redactions, signals, title.truncated() || desc.truncated()));
        return dto;
    }

    public List<SuggestionDto> list(UUID ticketId) {
        UUID tenant = TenantContext.require();
        return readTx.execute(s -> {
            tickets.findByIdAndTenantId(ticketId, tenant).orElseThrow(() -> ApiException.notFound("Ticket"));
            return repo.findByTenantIdAndTicketIdOrderByCreatedAtDescIdDesc(tenant, ticketId, PageRequest.of(0, 50)).stream().map(this::toDto).toList();
        });
    }

    // ---- internals ----------------------------------------------------------------------------------------------------

    private Context load(UUID tenant, UUID ticketId) {
        Ticket t = tickets.findByIdAndTenantId(ticketId, tenant).orElseThrow(() -> ApiException.notFound("Ticket"));
        List<TicketCategory> cats = categories.findByTenantIdOrderByNameAsc(tenant, PageRequest.of(0, 200));
        return new Context(t, cats, kb.publishedCandidates(tenant, 100));
    }

    /** Timeout + bounded retries + circuit breaker. Never throws: every failure becomes an Outcome with a reason. */
    private Outcome callProvider(AiRequest req) {
        if (!breaker.allow()) return new Outcome(null, "circuit_open", 0, 0);
        long start = System.nanoTime();
        long deadline = System.currentTimeMillis() + settings.totalDeadlineMs;
        int attempts = 0;
        String reason = "provider_error";
        for (int i = 0; i <= settings.maxRetries; i++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { reason = "deadline"; break; }
            attempts++;
            Future<AiResult> f = null;
            try {
                f = pool.submit(() -> {
                    faults.apply(settings.timeoutMs);
                    return provider.analyze(req);
                });
                AiResult r = f.get(Math.min(settings.timeoutMs, remaining), TimeUnit.MILLISECONDS);
                breaker.success();
                return new Outcome(r, null, attempts, (System.nanoTime() - start) / 1_000_000);
            } catch (TimeoutException e) {
                f.cancel(true);
                reason = "timeout";
            } catch (ExecutionException e) {
                reason = "provider_error";
                if (e.getCause() instanceof AiProviderException ape && !ape.retryable()) break;
                log.warn("AI provider attempt {} failed: {}", attempts, e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            } catch (RejectedExecutionException e) {
                reason = "overloaded";
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reason = "interrupted";
                break;
            }
            if (i < settings.maxRetries) {
                try {
                    Thread.sleep(Math.min(50L * attempts, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reason = "interrupted";
                    break;
                }
            }
        }
        breaker.failure();
        return new Outcome(null, reason, attempts, (System.nanoTime() - start) / 1_000_000);
    }

    private SuggestionDto persist(UUID tenant, AuthUser me, UUID ticketId, Context ctx, Outcome out, String promptHash,
                                  Map<String, Integer> redactions, int signals, boolean truncated) {
        AiResult r = out.result();
        Map<UUID, KbCandidate> byId = ctx.articles().stream().collect(Collectors.toMap(KbCandidate::id, a -> a, (a, b) -> a));
        TicketCategory cat = r.categoryName() == null ? null
                : ctx.categories().stream().filter(c -> c.getName().equalsIgnoreCase(r.categoryName().trim())).findFirst().orElse(null);
        Priority prio = null;
        try {
            if (r.priority() != null) prio = Priority.valueOf(r.priority().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) { /* unknown priority from the provider: dropped */ }
        double conf = Double.isNaN(r.confidence()) ? 0 : Math.max(0, Math.min(1, r.confidence()));
        List<ArticleRef> arts = new ArrayList<>();
        if (r.articles() != null) for (ScoredArticle a : r.articles()) {
            KbCandidate c = byId.get(a.id());                                  // ids invented by a provider are dropped
            if (c != null && arts.size() < 3) arts.add(new ArticleRef(c.id(), c.title(), Math.max(0, Math.min(1, a.score()))));
        }
        String reply = AiSanitizer.cleanOutput(r.reply(), 1500);
        if (reply != null && reply.isBlank()) reply = null;
        String summary = AiSanitizer.cleanOutput(r.summary(), 600);

        AiSuggestion s = new AiSuggestion();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenant);
        s.setTicketId(ticketId);
        s.setStatus(SuggestionStatus.PENDING_REVIEW);
        s.setProvider(provider.name());
        s.setSummary(summary == null || summary.isBlank() ? "(no summary)" : summary);
        s.setSuggestedCategoryId(cat == null ? null : cat.getId());
        s.setSuggestedCategoryName(cat == null ? null : cat.getName());
        s.setSuggestedPriority(prio);
        s.setConfidence(Math.round(conf * 100.0) / 100.0);
        String expl = AiSanitizer.cleanOutput(r.explanation(), 1000);
        s.setExplanation(expl == null || expl.isBlank() ? "(no explanation)" : expl);
        s.setRecommendedArticles(json(arts));
        s.setSuggestedReply(reply);
        s.setCreatedBy(me.userId());
        s.setCreatedAt(Instant.now());
        repo.saveAndFlush(s);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("suggestionId", s.getId().toString());
        meta.put("ticketId", ticketId.toString());
        meta.put("provider", provider.name());
        meta.put("providerDetails", Map.of("name", provider.name(), "attempts", out.attempts(), "latencyMs", out.latencyMs(),
                "deterministic", MockRuleBasedAiProvider.NAME.equals(provider.name())));
        meta.put("promptHash", promptHash);
        meta.put("features", r.features() == null ? Map.of() : r.features());
        meta.put("scores", Map.of("confidence", s.getConfidence(), "articles", arts.stream().map(a -> Map.of("id", a.id().toString(), "score", a.score())).toList()));
        meta.put("redactions", redactions);
        meta.put("injectionSignals", signals);
        meta.put("inputTruncated", truncated);
        meta.put("createdAt", s.getCreatedAt().toString());
        try {
            docs.put(tenant, META, s.getId().toString(), meta);
        } catch (RuntimeException e) {                                          // diagnostics only: never fail the user-visible result
            log.warn("AI suggestion metadata could not be stored: {}", e.getClass().getSimpleName());
            meters.counter("cx.ai.meta.failed").increment();
        }
        audit.record("AI_SUGGESTION_GENERATED", "AI_SUGGESTION", s.getId(), Map.of("ticketId", ticketId.toString(), "provider", provider.name(),
                "confidence", s.getConfidence(), "injectionSignals", signals, "redactions", redactions.values().stream().mapToInt(Integer::intValue).sum()));
        return toDto(s);
    }

    SuggestionDto toDto(AiSuggestion s) {
        List<ArticleRef> arts;
        try {
            arts = mapper.readValue(s.getRecommendedArticles(), ARTICLE_LIST);
        } catch (JsonProcessingException e) {
            arts = List.of();
        }
        return new SuggestionDto(s.getId(), s.getProvider(), true, s.getSummary(),
                s.getSuggestedCategoryId() == null ? null : new CategoryRef(s.getSuggestedCategoryId(), s.getSuggestedCategoryName()),
                s.getSuggestedPriority(), s.getConfidence(), s.getExplanation(), arts, s.getSuggestedReply(), s.getStatus().name(), false, null,
                s.getTicketId(), s.getCreatedAt(), s.getDecidedAt(), s.getDecisionReason());
    }

    private SuggestionDto degraded() {
        return new SuggestionDto(null, provider.name(), false, null, null, null, 0, null, List.of(), null, "DEGRADED", true, FALLBACK,
                null, null, null, null);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
