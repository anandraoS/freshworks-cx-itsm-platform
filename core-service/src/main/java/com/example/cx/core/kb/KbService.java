package com.example.cx.core.kb;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.PageParams;
import com.example.cx.core.common.PageResponse;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.docstore.DocumentStore;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.kb.KbDtos.ArticleDto;
import com.example.cx.core.kb.KbDtos.ArticleRequest;
import com.example.cx.core.kb.KbDtos.AuthorDto;
import com.example.cx.core.kb.KbDtos.KbCategoryDto;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.Visibility;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.criteria.Predicate;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Knowledge base. Authoritative metadata + CURRENT content live in relational tables (kb_article ...); every committed change
 * writes one immutable version snapshot to the {@link DocumentStore} (collection {@value #VERSIONS}). Nothing else is duplicated.
 *
 * Visibility rule: a CUSTOMER sees only PUBLISHED + PUBLIC articles (enforced here, not in the controller; a draft or internal id is
 * a 404 for them, and list filters can only narrow, never widen). Cache-aside (tenant-keyed): category list (5 min) and the
 * PUBLISHED+PUBLIC first-class list (60 s); both are evicted after the commit of any write.
 */
@Service
public class KbService {
    public static final String VERSIONS = "kb_article_version";
    static final String CATEGORIES_KEY = "kb:categories";
    static final String PUBLISHED_PREFIX = "kb:pub:";
    static final int MAX_CANDIDATES = 300, MAX_CATEGORIES = 200;

    private final KbArticleRepository articles;
    private final KbCategoryRepository categories;
    private final KbArticleTicketRepository links;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final DocumentStore docs;
    private final CacheService cache;
    private final AuditService audit;
    private final TransactionTemplate readTx;

    public KbService(KbArticleRepository articles, KbCategoryRepository categories, KbArticleTicketRepository links,
                     TicketRepository tickets, UserRepository users, DocumentStore docs, CacheService cache, AuditService audit,
                     PlatformTransactionManager txm) {
        this.articles = articles;
        this.categories = categories;
        this.links = links;
        this.tickets = tickets;
        this.users = users;
        this.docs = docs;
        this.cache = cache;
        this.audit = audit;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    public PageResponse<ArticleDto> list(String q, UUID categoryId, ArticleStatus status, Visibility visibility, Integer page, Integer size) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        boolean staff = me.isStaff();
        Pageable p = PageParams.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id")));
        if (!staff) {
            if ((status != null && status != ArticleStatus.PUBLISHED) || (visibility != null && visibility != Visibility.PUBLIC))
                return new PageResponse<>(List.of(), p.getPageNumber(), p.getPageSize(), 0, 0);
            status = ArticleStatus.PUBLISHED;
            visibility = Visibility.PUBLIC;
        }
        String query = q == null ? "" : q.trim();
        if (query.length() > 200) query = query.substring(0, 200);
        final ArticleStatus st = status;
        final Visibility vis = visibility;
        if (query.isBlank()) {
            if (st == ArticleStatus.PUBLISHED && vis == Visibility.PUBLIC) {
                String key = PUBLISHED_PREFIX + (staff ? "s" : "c") + ":" + categoryId + ":" + p.getPageNumber() + ":" + p.getPageSize();
                return cache.getOrLoad(tenant, key, Duration.ofSeconds(60), new TypeReference<PageResponse<ArticleDto>>() {},
                        () -> readTx.execute(s -> plainPage(tenant, categoryId, st, vis, p, staff)));
            }
            return readTx.execute(s -> plainPage(tenant, categoryId, st, vis, p, staff));
        }
        final String qq = query;
        return readTx.execute(s -> searchPage(tenant, qq, categoryId, st, vis, p, staff));
    }

    private PageResponse<ArticleDto> plainPage(UUID tenant, UUID categoryId, ArticleStatus st, Visibility vis, Pageable p, boolean staff) {
        Page<KbArticle> page = articles.findAll(filters(tenant, categoryId, st, vis), p);
        return new PageResponse<>(toDtos(tenant, page.getContent(), staff), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Portable search: DB narrows with LIKE on any token (bounded candidate set, newest first), the JVM scores and ranks them
     * (see {@link KbSearchScorer}), then the requested page is sliced. totalElements = matches within the candidate bound.
     */
    private PageResponse<ArticleDto> searchPage(UUID tenant, String q, UUID categoryId, ArticleStatus st, Visibility vis, Pageable p, boolean staff) {
        Set<String> tokens = KbSearchScorer.tokens(q);
        String norm = KbSearchScorer.normalise(q);
        if (tokens.isEmpty() && norm.length() >= 2) tokens = Set.of(norm.length() > 40 ? norm.substring(0, 40) : norm);
        if (tokens.isEmpty()) return new PageResponse<>(List.of(), p.getPageNumber(), p.getPageSize(), 0, 0);
        final Set<String> toks = tokens;
        Specification<KbArticle> spec = filters(tenant, categoryId, st, vis).and((root, cq, cb) -> {
            List<Predicate> any = new ArrayList<>();
            for (String tok : toks) {
                String like = "%" + escapeLike(tok) + "%";
                any.add(cb.like(cb.lower(root.get("title")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("summary")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("body")), like, '\\'));
            }
            return cb.or(any.toArray(Predicate[]::new));
        });
        List<KbArticle> candidates = articles.findAll(spec, PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id")))).getContent();
        Map<UUID, Integer> scores = new HashMap<>();
        for (KbArticle a : candidates) scores.put(a.getId(), KbSearchScorer.score(toks, norm, a.getTitle(), a.getSummary(), a.getBody()));
        List<KbArticle> ranked = candidates.stream().filter(a -> scores.get(a.getId()) > 0)
                .sorted(Comparator.comparingInt((KbArticle a) -> scores.get(a.getId())).reversed()
                        .thenComparing(KbArticle::getUpdatedAt, Comparator.reverseOrder()).thenComparing(KbArticle::getId))
                .toList();
        int from = (int) Math.min((long) p.getPageNumber() * p.getPageSize(), ranked.size());
        int to = Math.min(from + p.getPageSize(), ranked.size());
        int totalPages = (int) Math.ceil(ranked.size() / (double) p.getPageSize());
        return new PageResponse<>(toDtos(tenant, ranked.subList(from, to), staff), p.getPageNumber(), p.getPageSize(), ranked.size(), totalPages);
    }

    public ArticleDto get(UUID id) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        return readTx.execute(s -> toDtos(tenant, List.of(visibleOrThrow(tenant, id, me)), me.isStaff()).get(0));
    }

    /** Version history (staff only), newest first. Bounded to {@value DocumentStore#MAX_LIST} snapshots. */
    public List<ArticleDto> versions(UUID id) {
        UUID tenant = TenantContext.require();
        KbArticle a = readTx.execute(s -> articles.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Article")));
        List<Map<String, Object>> snaps = new ArrayList<>(docs.list(tenant, VERSIONS, id + ":", DocumentStore.MAX_LIST));
        java.util.Collections.reverse(snaps);
        List<ArticleDto> out = new ArrayList<>();
        for (Map<String, Object> d : snaps) {
            Object editor = d.get("editedBy");
            out.add(new ArticleDto(a.getId(), str(d, "title"), a.getSlug(), str(d, "body"), str(d, "summary"),
                    d.get("categoryId") == null ? null : UUID.fromString(str(d, "categoryId")), (String) d.get("categoryName"),
                    Visibility.valueOf(str(d, "visibility")), ArticleStatus.valueOf(str(d, "status")), ((Number) d.get("version")).longValue(),
                    editor == null ? null : new AuthorDto(UUID.fromString(editor.toString()), (String) d.get("editedByName")),
                    a.getCreatedAt(), Instant.parse(str(d, "editedAt")), List.of()));
        }
        return out;
    }

    public List<KbCategoryDto> categories() {
        UUID tenant = TenantContext.require();
        return cache.getOrLoad(tenant, CATEGORIES_KEY, Duration.ofMinutes(5), new TypeReference<List<KbCategoryDto>>() {},
                () -> readTx.execute(s -> categories.findByTenantIdOrderByNameAsc(tenant, PageRequest.of(0, MAX_CATEGORIES)).stream()
                        .map(c -> new KbCategoryDto(c.getId(), c.getName(), c.getSlug())).toList()));
    }

    /** Published articles (title/summary only) for the AI recommender. Caller must have the tenant bound. */
    public List<KbCandidate> publishedCandidates(UUID tenant, int limit) {
        return articles.publishedCandidates(tenant, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    // ---- writes (AGENT+, enforced by the controller) ------------------------------------------------------------------

    @Transactional
    public ArticleDto create(ArticleRequest r) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        requireCategory(tenant, r.categoryId());
        Instant now = Instant.now();
        KbArticle a = new KbArticle();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenant);
        a.setTitle(r.title().trim());
        a.setSlug(uniqueSlug(tenant, r.title()));
        a.setBody(r.body().trim());
        a.setSummary(summaryOrDerived(r.summary(), a.getBody()));
        a.setCategoryId(r.categoryId());
        a.setVisibility(r.visibility());
        a.setStatus(ArticleStatus.DRAFT);
        a.setAuthorId(me.userId());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        articles.saveAndFlush(a);
        snapshot(tenant, a, me.userId(), "created");
        audit.record("KB_ARTICLE_CREATED", "KB_ARTICLE", a.getId(), Map.of("title", a.getTitle(), "visibility", a.getVisibility().name()));
        evictPublished(tenant);
        return toDtos(tenant, List.of(a), true).get(0);
    }

    @Transactional
    public ArticleDto update(UUID id, ArticleRequest r) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        KbArticle a = articles.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Article"));
        if (r.version() != null && r.version() != a.displayVersion())
            throw ApiException.conflict("Article was modified by someone else (current version " + a.displayVersion() + ")");
        requireCategory(tenant, r.categoryId());
        String title = r.title().trim(), body = r.body().trim(), summary = summaryOrDerived(r.summary(), body);
        boolean changed = !title.equals(a.getTitle()) || !body.equals(a.getBody()) || !summary.equals(a.getSummary())
                || !java.util.Objects.equals(r.categoryId(), a.getCategoryId()) || r.visibility() != a.getVisibility();
        if (changed) {
            a.setTitle(title);
            a.setBody(body);
            a.setSummary(summary);
            a.setCategoryId(r.categoryId());
            a.setVisibility(r.visibility());
            a.setUpdatedAt(Instant.now());
            articles.saveAndFlush(a);
            snapshot(tenant, a, me.userId(), "updated");
            audit.record("KB_ARTICLE_UPDATED", "KB_ARTICLE", a.getId(), Map.of("version", a.displayVersion(), "visibility", a.getVisibility().name()));
            evictPublished(tenant);
        }
        return toDtos(tenant, List.of(a), true).get(0);
    }

    @Transactional
    public ArticleDto publish(UUID id) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        KbArticle a = articles.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Article"));
        if (a.getStatus() != ArticleStatus.PUBLISHED) {
            Instant now = Instant.now();
            a.setStatus(ArticleStatus.PUBLISHED);
            a.setPublishedAt(now);
            a.setUpdatedAt(now);
            articles.saveAndFlush(a);
            snapshot(tenant, a, me.userId(), "published");
            audit.record("KB_ARTICLE_PUBLISHED", "KB_ARTICLE", a.getId(), Map.of("version", a.displayVersion(), "visibility", a.getVisibility().name()));
            evictPublished(tenant);
        }
        return toDtos(tenant, List.of(a), true).get(0);
    }

    @Transactional
    public ArticleDto linkTicket(UUID articleId, UUID ticketId) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        KbArticle a = articles.findByIdAndTenantId(articleId, tenant).orElseThrow(() -> ApiException.notFound("Article"));
        tickets.findByIdAndTenantId(ticketId, tenant).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!links.existsByTenantIdAndArticleIdAndTicketId(tenant, articleId, ticketId)) {
            KbArticleTicket l = new KbArticleTicket();
            l.setId(UUID.randomUUID());
            l.setTenantId(tenant);
            l.setArticleId(articleId);
            l.setTicketId(ticketId);
            l.setLinkedBy(me.userId());
            l.setCreatedAt(Instant.now());
            links.save(l);
            audit.record("KB_ARTICLE_TICKET_LINKED", "KB_ARTICLE", articleId, Map.of("ticketId", ticketId.toString()));
        }
        return toDtos(tenant, List.of(a), true).get(0);
    }

    @Transactional
    public KbCategoryDto createCategory(String rawName) {
        UUID tenant = TenantContext.require();
        String name = rawName.trim();
        if (categories.existsByTenantIdAndNameIgnoreCase(tenant, name)) throw ApiException.conflict("A category with this name already exists");
        KbCategory c = new KbCategory();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenant);
        c.setName(name);
        String base = slugify(name, 60), slug = base;
        for (int i = 2; categories.existsByTenantIdAndSlug(tenant, slug); i++) slug = base + "-" + i;
        c.setSlug(slug);
        c.setCreatedAt(Instant.now());
        categories.save(c);
        audit.record("KB_CATEGORY_CREATED", "KB_CATEGORY", c.getId(), Map.of("name", name));
        TxHooks.afterCommit(() -> cache.evict(tenant, CATEGORIES_KEY));
        return new KbCategoryDto(c.getId(), c.getName(), c.getSlug());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private KbArticle visibleOrThrow(UUID tenant, UUID id, AuthUser me) {
        KbArticle a = articles.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Article"));
        if (!me.isStaff() && (a.getStatus() != ArticleStatus.PUBLISHED || a.getVisibility() != Visibility.PUBLIC)) throw ApiException.notFound("Article");
        return a;
    }

    private void requireCategory(UUID tenant, UUID categoryId) {
        if (categoryId != null && categories.findByIdAndTenantId(categoryId, tenant).isEmpty())
            throw ApiException.validation("categoryId", "unknown category");
    }

    private static Specification<KbArticle> filters(UUID tenant, UUID categoryId, ArticleStatus st, Visibility vis) {
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenant));
            if (categoryId != null) ps.add(cb.equal(root.get("categoryId"), categoryId));
            if (st != null) ps.add(cb.equal(root.get("status"), st));
            if (vis != null) ps.add(cb.equal(root.get("visibility"), vis));
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** One batched query each for category names, author names and ticket links (no N+1). Links are never exposed to customers. */
    private List<ArticleDto> toDtos(UUID tenant, List<KbArticle> list, boolean includeLinks) {
        if (list.isEmpty()) return List.of();
        Map<UUID, String> catNames = categories.findByTenantIdAndIdIn(tenant,
                list.stream().map(KbArticle::getCategoryId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(KbCategory::getId, KbCategory::getName));
        Map<UUID, String> authors = users.findByTenantIdAndIdIn(tenant, list.stream().map(KbArticle::getAuthorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        Map<UUID, List<UUID>> related = new HashMap<>();
        if (includeLinks) {
            for (KbArticleTicket l : links.findByTenantIdAndArticleIdInOrderByCreatedAtAsc(tenant, list.stream().map(KbArticle::getId).toList()))
                related.computeIfAbsent(l.getArticleId(), k -> new ArrayList<>()).add(l.getTicketId());
        }
        return list.stream().map(a -> new ArticleDto(a.getId(), a.getTitle(), a.getSlug(), a.getBody(), a.getSummary(), a.getCategoryId(),
                a.getCategoryId() == null ? null : catNames.get(a.getCategoryId()), a.getVisibility(), a.getStatus(), a.displayVersion(),
                new AuthorDto(a.getAuthorId(), authors.getOrDefault(a.getAuthorId(), "Unknown")), a.getCreatedAt(), a.getUpdatedAt(),
                related.getOrDefault(a.getId(), List.of()))).toList();
    }

    /** Version snapshot -> DocumentStore. Written last in the transaction; a failure aborts (rolls back) the article change. */
    void snapshot(UUID tenant, KbArticle a, UUID editorId, String note) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("articleId", a.getId().toString());
        d.put("version", a.displayVersion());
        d.put("title", a.getTitle());
        d.put("summary", a.getSummary());
        d.put("body", a.getBody());
        d.put("categoryId", a.getCategoryId() == null ? null : a.getCategoryId().toString());
        d.put("categoryName", a.getCategoryId() == null ? null : categories.findByIdAndTenantId(a.getCategoryId(), tenant).map(KbCategory::getName).orElse(null));
        d.put("visibility", a.getVisibility().name());
        d.put("status", a.getStatus().name());
        d.put("editedBy", editorId.toString());
        d.put("editedByName", users.findByIdAndTenantId(editorId, tenant).map(AppUser::getDisplayName).orElse("Unknown"));
        d.put("editedAt", Instant.now().toString());
        d.put("note", note);
        docs.put(tenant, VERSIONS, a.getId() + ":" + String.format("%08d", a.displayVersion()), d);
    }

    private void evictPublished(UUID tenant) { TxHooks.afterCommit(() -> cache.evictByPrefix(tenant, PUBLISHED_PREFIX)); }

    private String uniqueSlug(UUID tenant, String title) {
        String base = slugify(title, 80), slug = base;
        for (int i = 2; articles.existsByTenantIdAndSlug(tenant, slug); i++) {
            slug = base + "-" + i;
            if (i > 200) return base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return slug;
    }

    static String slugify(String s, int max) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (n.isEmpty()) n = "article";
        return n.length() > max ? n.substring(0, max).replaceAll("-+$", "") : n;
    }

    private static String summaryOrDerived(String summary, String body) {
        String s = summary == null ? "" : summary.trim();
        if (!s.isEmpty()) return s;
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "..." : flat;
    }

    private static String escapeLike(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
