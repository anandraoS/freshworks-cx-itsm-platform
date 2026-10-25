package com.example.cx.core.common;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds bounded Pageables: size 1..100, sort field whitelist. Unbounded queries are impossible by construction. */
public final class PageParams {
    public static final int MAX_SIZE = 100;
    private PageParams() {}

    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowed, Sort defaultSort) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0) throw ApiException.validation("page", "must be >= 0");
        if (s < 1 || s > MAX_SIZE) throw ApiException.validation("size", "must be between 1 and " + MAX_SIZE);
        Sort sorting = defaultSort;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!allowed.contains(field)) throw ApiException.validation("sort", "unsupported sort field '" + field + "'");
            Sort.Direction dir = Sort.Direction.ASC;
            if (parts.length > 1) {
                String d = parts[1].trim();
                if ("desc".equalsIgnoreCase(d)) dir = Sort.Direction.DESC;
                else if (!"asc".equalsIgnoreCase(d)) throw ApiException.validation("sort", "direction must be asc or desc");
            }
            sorting = Sort.by(dir, field).and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(p, s, sorting);
    }
}
