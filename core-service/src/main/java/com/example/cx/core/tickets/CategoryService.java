package com.example.cx.core.tickets;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.TicketDtos.CategoryDto;
import com.example.cx.core.tickets.TicketDtos.CategoryRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    public static final String CACHE_KEY = "categories:list";
    private static final int MAX_CATEGORIES = 200;

    private final TicketCategoryRepository repo;
    private final TicketRepository tickets;
    private final CacheService cache;
    private final AuditService audit;

    public CategoryService(TicketCategoryRepository repo, TicketRepository tickets, CacheService cache, AuditService audit) {
        this.repo = repo;
        this.tickets = tickets;
        this.cache = cache;
        this.audit = audit;
    }

    /** Cache-aside, tenant-keyed, 5 minutes, evicted (after commit) on every write. */
    public List<CategoryDto> list() {
        UUID t = TenantContext.require();
        return cache.getOrLoad(t, CACHE_KEY, Duration.ofMinutes(5), new TypeReference<List<CategoryDto>>() {},
                () -> repo.findByTenantIdOrderByNameAsc(t, PageRequest.of(0, MAX_CATEGORIES)).stream().map(CategoryService::toDto).toList());
    }

    @Transactional
    public CategoryDto create(CategoryRequest r) {
        UUID t = TenantContext.require();
        String name = r.name().trim();
        if (repo.existsByTenantIdAndNameIgnoreCase(t, name)) throw ApiException.conflict("A category with this name already exists");
        TicketCategory c = new TicketCategory();
        c.setId(UUID.randomUUID());
        c.setTenantId(t);
        c.setName(name);
        c.setType(r.type());
        c.setDefaultPriority(r.defaultPriority());
        c.setCreatedAt(Instant.now());
        repo.save(c);
        audit.record("CATEGORY_CREATED", "TICKET_CATEGORY", c.getId(), Map.of("name", name));
        evict(t);
        return toDto(c);
    }

    @Transactional
    public CategoryDto update(UUID id, CategoryRequest r) {
        UUID t = TenantContext.require();
        TicketCategory c = repo.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Category"));
        String name = r.name().trim();
        if (!c.getName().equalsIgnoreCase(name) && repo.existsByTenantIdAndNameIgnoreCase(t, name))
            throw ApiException.conflict("A category with this name already exists");
        c.setName(name);
        c.setType(r.type());
        c.setDefaultPriority(r.defaultPriority());
        repo.save(c);
        audit.record("CATEGORY_UPDATED", "TICKET_CATEGORY", c.getId(), Map.of("name", name));
        evict(t);
        return toDto(c);
    }

    @Transactional
    public void delete(UUID id) {
        UUID t = TenantContext.require();
        TicketCategory c = repo.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Category"));
        if (tickets.countByTenantIdAndCategoryId(t, id) > 0) throw ApiException.conflict("Category is used by existing tickets");
        repo.delete(c);
        audit.record("CATEGORY_DELETED", "TICKET_CATEGORY", id, Map.of("name", c.getName()));
        evict(t);
    }

    private void evict(UUID tenant) { TxHooks.afterCommit(() -> cache.evict(tenant, CACHE_KEY)); }

    static CategoryDto toDto(TicketCategory c) { return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getDefaultPriority()); }
}
