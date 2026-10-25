package com.example.cx.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Severity;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketStatus;
import com.example.cx.core.tickets.TicketType;
import com.example.cx.core.workflow.WorkflowModel.Action;
import com.example.cx.core.workflow.WorkflowModel.Condition;
import com.example.cx.core.workflow.WorkflowModel.Rule;
import com.example.cx.core.workflow.WorkflowModel.Transition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {

    static Rule rule(Condition c, Action a) { return new Rule("r", c == null ? List.of() : List.of(c), List.of(a)); }

    static List<String> check(Rule r) { return WorkflowValidator.validate(List.of(r), List.of()); }

    @Test
    void validRuleSetPasses() {
        List<Rule> rules = List.of(
                rule(new Condition("priority", "GT", "MEDIUM"), new Action("ESCALATE", Map.of())),
                rule(new Condition("title", "CONTAINS", "vpn"), new Action("ADD_TAG", Map.of("tag", "network"))),
                rule(new Condition("status", "IN", List.of("NEW", "OPEN")), new Action("SET_PRIORITY", Map.of("priority", "HIGH"))),
                rule(new Condition("escalationLevel", "LT", 3), new Action("NOTIFY", Map.of("role", "MANAGER", "message", "look"))),
                rule(null, new Action("ASSIGN_TO_ROLE_LEAST_LOADED", Map.of("role", "AGENT"))));
        assertThat(WorkflowValidator.validate(rules, List.of())).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsOperatorsAndActions() {
        assertThat(check(rule(new Condition("password", "EQ", "x"), new Action("ESCALATE", Map.of())))).anyMatch(e -> e.contains("unsupported field"));
        assertThat(check(rule(new Condition("priority", "REGEX", "x"), new Action("ESCALATE", Map.of())))).anyMatch(e -> e.contains("unsupported operator"));
        assertThat(check(rule(new Condition("title", "GT", "x"), new Action("ESCALATE", Map.of())))).anyMatch(e -> e.contains("not allowed for field"));
        assertThat(check(rule(null, new Action("RUN_SCRIPT", Map.of("code", "x"))))).anyMatch(e -> e.contains("unsupported action"));
        assertThat(check(rule(new Condition("priority", "EQ", "SUPER"), new Action("ESCALATE", Map.of())))).anyMatch(e -> e.contains("not a valid priority"));
    }

    @Test
    void rejectsExpressionAndScriptInjectionAttempts() {
        for (String evil : new String[]{"${jndi:ldap://x}", "#{T(java.lang.Runtime).getRuntime().exec('calc')}", "T(java.lang.System).exit(1)", "{{7*7}}", "<%= 1 %>"}) {
            assertThat(check(rule(new Condition("title", "CONTAINS", evil), new Action("ESCALATE", Map.of()))))
                    .as(evil).anyMatch(e -> e.contains("expression syntax"));
            assertThat(check(rule(null, new Action("NOTIFY", Map.of("message", evil)))))
                    .as(evil).anyMatch(e -> e.contains("expression syntax"));
        }
        // unknown parameters are refused rather than ignored
        assertThat(check(rule(null, new Action("ADD_TAG", Map.of("tag", "ok", "script", "x"))))).anyMatch(e -> e.contains("unknown parameter"));
        assertThat(check(rule(null, new Action("ADD_TAG", Map.of("tag", "Not Valid!"))))).anyMatch(e -> e.contains("params.tag"));
    }

    @Test
    void enforcesSizeLimits() {
        List<Rule> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add(rule(null, new Action("ESCALATE", Map.of())));
        assertThat(WorkflowValidator.validate(many, List.of())).anyMatch(e -> e.contains("at most 50"));
        Rule noAction = new Rule("empty", List.of(), List.of());
        assertThat(check(noAction)).anyMatch(e -> e.contains("at least one action"));
    }

    @Test
    void validatesTransitions() {
        assertThat(WorkflowValidator.validate(List.of(), List.of(new Transition("NEW", "OPEN"), new Transition("OPEN", "RESOLVED")))).isEmpty();
        assertThat(WorkflowValidator.validate(List.of(), List.of(new Transition("NEW", "BOGUS")))).anyMatch(e -> e.contains("valid statuses"));
        assertThat(WorkflowValidator.validate(List.of(), List.of(new Transition("OPEN", "OPEN")))).anyMatch(e -> e.contains("must differ"));
        assertThat(WorkflowValidator.validate(List.of(), List.of(new Transition("NEW", "OPEN"), new Transition("NEW", "OPEN")))).anyMatch(e -> e.contains("duplicate"));
        // NEW can never reach RESOLVED -> rejected so a tenant cannot lock all tickets
        assertThat(WorkflowValidator.validate(List.of(), List.of(new Transition("NEW", "OPEN")))).anyMatch(e -> e.contains("NEW to RESOLVED"));
    }

    // ---- engine semantics (pure condition evaluation) ----

    static Ticket sample() {
        Ticket t = new Ticket();
        t.setPriority(Priority.HIGH);
        t.setSeverity(Severity.MAJOR);
        t.setType(TicketType.IT_SERVICE);
        t.setStatus(TicketStatus.OPEN);
        t.setTitle("VPN is down for the whole office");
        t.setDescription("Nobody can connect");
        t.setTagList(List.of("network", "vip"));
        t.setEscalationLevel(1);
        return t;
    }

    @Test
    void operatorsEvaluateAsDocumented() {
        Map<String, Object> f = WorkflowEngine.facts(sample(), "Incident");
        assertThat(WorkflowEngine.test(new Condition("priority", "EQ", "HIGH"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("priority", "NE", "HIGH"), f)).isFalse();
        assertThat(WorkflowEngine.test(new Condition("priority", "GT", "MEDIUM"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("priority", "LT", "URGENT"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("priority", "GT", "HIGH"), f)).isFalse();
        assertThat(WorkflowEngine.test(new Condition("severity", "IN", List.of("MAJOR", "CRITICAL")), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("title", "CONTAINS", "vpn"), f)).isTrue(); // case-insensitive
        assertThat(WorkflowEngine.test(new Condition("tags", "CONTAINS", "vip"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("tags", "IN", List.of("nope", "network")), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("escalationLevel", "GT", 0), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("escalationLevel", "EQ", 1), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("category", "EQ", "incident"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("type", "IN", "SUPPORT,IT_SERVICE"), f)).isTrue();
        assertThat(WorkflowEngine.test(new Condition("status", "EQ", "CLOSED"), f)).isFalse();
    }
}
