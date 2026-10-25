package com.example.cx.core.identity;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.identity.IdentityDtos.IntegrationsDto;
import com.example.cx.core.identity.IdentityDtos.TenantSettingsDto;
import com.example.cx.core.tenancy.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantSettingsService {
    public static final String CACHE_KEY = "settings";
    private final OrganizationRepository orgs;
    private final AuditService audit;
    private final CacheService cache;

    public TenantSettingsService(OrganizationRepository orgs, AuditService audit, CacheService cache) {
        this.orgs = orgs;
        this.audit = audit;
        this.cache = cache;
    }

    /** Admin view: always read from the DB (contains the webhook URL, which must not sit in a shared cache). */
    @Transactional(readOnly = true)
    public TenantSettingsDto get() { return load(TenantContext.require()); }

    /** Non-sensitive subset used by SLA math on every ticket write: cache-aside, 5 min, evicted on settings update. */
    public record CalendarSettings(String timezone, String start, String end, List<String> days) {}

    public CalendarSettings calendar() {
        UUID t = TenantContext.require();
        return cache.getOrLoad(t, CACHE_KEY, Duration.ofMinutes(5), new TypeReference<CalendarSettings>() {}, () -> {
            TenantSettingsDto d = load(t);
            return new CalendarSettings(d.timezone(), d.businessHoursStart(), d.businessHoursEnd(), d.businessDays());
        });
    }

    @Transactional(readOnly = true)
    public TenantSettingsDto load(UUID tenantId) {
        Organization o = orgs.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        return toDto(o);
    }

    @Transactional
    public TenantSettingsDto update(TenantSettingsDto r) {
        UUID t = TenantContext.require();
        validate(r);
        Organization o = orgs.findById(t).orElseThrow(() -> ApiException.notFound("Tenant"));
        o.setName(r.name().trim());
        o.setTimezone(r.timezone());
        o.setBusinessHoursStart(r.businessHoursStart());
        o.setBusinessHoursEnd(r.businessHoursEnd());
        o.setBusinessDays(String.join(",", r.businessDays()));
        o.setDefaultLanguage(r.defaultLanguage());
        o.setEmailProvider(blankToNull(r.integrations().emailProvider()));
        o.setSlackWebhookUrl(blankToNull(r.integrations().slackWebhookUrl()));
        o.setAiProvider(blankToNull(r.integrations().aiProvider()));
        orgs.save(o);
        audit.record("TENANT_SETTINGS_UPDATED", "TENANT", t, Map.of("name", o.getName(), "timezone", o.getTimezone(),
                "businessHours", o.getBusinessHoursStart() + "-" + o.getBusinessHoursEnd(), "days", o.getBusinessDays()));
        cache.evict(t, CACHE_KEY);
        return toDto(o);
    }

    public static TenantSettingsDto toDto(Organization o) {
        return new TenantSettingsDto(o.getName(), o.getTimezone(), o.getBusinessHoursStart(), o.getBusinessHoursEnd(),
                Arrays.asList(o.getBusinessDays().split(",")), o.getDefaultLanguage(),
                new IntegrationsDto(o.getEmailProvider(), o.getSlackWebhookUrl(), o.getAiProvider()));
    }

    public static Set<DayOfWeek> parseDays(List<String> days) {
        Set<DayOfWeek> s = EnumSet.noneOf(DayOfWeek.class);
        for (String d : days) {
            s.add(switch (d) {
                case "MON" -> DayOfWeek.MONDAY; case "TUE" -> DayOfWeek.TUESDAY; case "WED" -> DayOfWeek.WEDNESDAY;
                case "THU" -> DayOfWeek.THURSDAY; case "FRI" -> DayOfWeek.FRIDAY; case "SAT" -> DayOfWeek.SATURDAY;
                case "SUN" -> DayOfWeek.SUNDAY; default -> throw new IllegalArgumentException(d);
            });
        }
        return s;
    }

    static void validate(TenantSettingsDto r) {
        try { ZoneId.of(r.timezone()); } catch (DateTimeException e) { throw ApiException.validation("timezone", "unknown time zone"); }
        if (!LocalTime.parse(r.businessHoursStart()).isBefore(LocalTime.parse(r.businessHoursEnd())))
            throw ApiException.validation("businessHoursEnd", "must be after businessHoursStart");
        if (r.businessDays().stream().collect(Collectors.toSet()).size() != r.businessDays().size())
            throw ApiException.validation("businessDays", "must not contain duplicates");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
