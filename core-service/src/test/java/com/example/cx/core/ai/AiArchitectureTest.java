package com.example.cx.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Source-level guard rails: "AI output never triggers an action" is enforced structurally, not just by convention. */
class AiArchitectureTest {
    private static List<Path> aiSources() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/example/cx/core/ai"))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void onlyTheDecisionServiceCanReachTheTicketWriteApi() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : aiSources()) {
            String src = Files.readString(p);
            String name = p.getFileName().toString();
            boolean writesTickets = src.contains("TicketService") || src.contains("TicketCommandService") || src.contains(".addComment(")
                    || src.contains(".transition(") || src.contains(".assign(");
            if (writesTickets && !name.equals("AiDecisionService.java")) offenders.add(name);
        }
        assertThat(offenders).as("AI classes that can write to tickets").isEmpty();
    }

    @Test
    void providersAreIsolatedFromPersistenceTenancyAndSecurity() throws IOException {
        for (Path p : aiSources()) {
            String name = p.getFileName().toString();
            if (!name.equals("MockRuleBasedAiProvider.java") && !name.equals("HttpAiProvider.java")) continue;
            String src = Files.readString(p);
            assertThat(src).as(name).doesNotContain("Repository", "TenantContext", "CurrentUser", "EntityManager", "TicketService", "DocumentStore", "AuditService");
        }
    }

    @Test
    void approvalPostsAsPublicAiGeneratedOnlyInsideTheDecisionService() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/example/cx/core/ai/AiDecisionService.java"));
        assertThat(src).contains("addComment(t.getId(), finalText, Visibility.PUBLIC, true)");
        assertThat(src.split("addComment\\(", -1).length - 1).as("exactly one call site").isEqualTo(1);
    }
}
