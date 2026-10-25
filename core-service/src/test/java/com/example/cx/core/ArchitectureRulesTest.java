package com.example.cx.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.audit.AuditEventRepository;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cheap, source/bytecode level guard rails for the security-critical conventions. */
class ArchitectureRulesTest {
    /** The ONLY classes allowed to bind a tenant (verified JWT filter, server-side login/registration, system jobs, seeding). */
    private static final Set<String> MAY_BIND_TENANT = Set.of(
            "TenantContext.java", "JwtAuthFilter.java", "AuthService.java", "TenantProvisioner.java", "SlaMonitorJob.java",
            "TicketCommandService.java", "DemoDataSeeder.java");

    @Test
    void tenantIsBoundOnlyByTheAllowedComponents() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                boolean binds = src.contains("TenantContext.set(") || src.contains("TenantContext.callAs(") || src.contains("TenantContext.runAs(");
                if (binds && !MAY_BIND_TENANT.contains(p.getFileName().toString())) offenders.add(p.toString());
            }
        }
        assertThat(offenders).as("classes that bind a tenant outside the allow-list").isEmpty();
    }

    @Test
    void controllersNeverAcceptATenantIdFromTheClient() {
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                for (var p : m.getParameters()) {
                    String n = p.getName().toLowerCase();
                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    boolean tenantParam = n.contains("tenant") || (rp != null && rp.value().toLowerCase().contains("tenant"));
                    assertThat(tenantParam).as("%s.%s takes a tenant parameter", c.getSimpleName(), m.getName()).isFalse();
                }
            }
        }
    }

    @Test
    void controllersNeverExposeEntities() {
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                for (Class<?> leaked : typesIn(m.getGenericReturnType())) assertThat(leaked.isAnnotationPresent(Entity.class))
                        .as("%s.%s leaks entity %s", c.getSimpleName(), m.getName(), leaked.getSimpleName()).isFalse();
                for (var p : m.getParameters()) for (Class<?> leaked : typesIn(p.getParameterizedType()))
                    assertThat(leaked.isAnnotationPresent(Entity.class)).as("%s.%s accepts entity %s", c.getSimpleName(), m.getName(), leaked.getSimpleName()).isFalse();
            }
        }
    }

    @Test
    void auditRepositoryIsAppendOnly() {
        List<String> names = java.util.Arrays.stream(AuditEventRepository.class.getMethods()).map(Method::getName).toList();
        assertThat(names).doesNotContain("delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch", "saveAll");
        assertThat(AuditEventRepository.class.getInterfaces()).extracting(Class::getSimpleName).doesNotContain("CrudRepository", "JpaRepository");
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.cx.core")) {
            try { out.add(Class.forName(bd.getBeanClassName())); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        }
        assertThat(out).hasSizeGreaterThanOrEqualTo(8);
        return out;
    }

    private static List<Class<?>> typesIn(Type t) {
        List<Class<?>> out = new ArrayList<>();
        if (t instanceof Class<?> c) out.add(c);
        else if (t instanceof ParameterizedType pt) {
            out.addAll(typesIn(pt.getRawType()));
            for (Type a : pt.getActualTypeArguments()) out.addAll(typesIn(a));
        }
        return out;
    }
}
