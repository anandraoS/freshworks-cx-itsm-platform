package com.example.cx.core.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TicketStateMachineTest {

    @Test
    void defaultTransitionsMatchTheContractExactly() {
        assertThat(TicketStateMachine.next(TicketStatus.NEW)).containsExactlyInAnyOrder(TicketStatus.OPEN);
        assertThat(TicketStateMachine.next(TicketStatus.OPEN)).containsExactlyInAnyOrder(TicketStatus.PENDING, TicketStatus.ON_HOLD, TicketStatus.RESOLVED);
        assertThat(TicketStateMachine.next(TicketStatus.PENDING)).containsExactlyInAnyOrder(TicketStatus.OPEN, TicketStatus.RESOLVED);
        assertThat(TicketStateMachine.next(TicketStatus.ON_HOLD)).containsExactlyInAnyOrder(TicketStatus.OPEN);
        assertThat(TicketStateMachine.next(TicketStatus.RESOLVED)).containsExactlyInAnyOrder(TicketStatus.CLOSED, TicketStatus.REOPENED);
        assertThat(TicketStateMachine.next(TicketStatus.CLOSED)).containsExactlyInAnyOrder(TicketStatus.REOPENED);
        assertThat(TicketStateMachine.next(TicketStatus.REOPENED)).containsExactlyInAnyOrder(TicketStatus.OPEN, TicketStatus.RESOLVED);
    }

    @Test
    void everyOtherPairIsRejected() {
        int allowed = 0, rejected = 0;
        for (TicketStatus from : TicketStatus.values())
            for (TicketStatus to : TicketStatus.values()) {
                if (TicketStateMachine.canTransition(from, to)) allowed++; else rejected++;
            }
        assertThat(allowed).isEqualTo(1 + 3 + 2 + 1 + 2 + 1 + 2);
        assertThat(rejected).isEqualTo(49 - 12);
        assertThat(TicketStateMachine.canTransition(TicketStatus.NEW, TicketStatus.RESOLVED)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.CLOSED, TicketStatus.OPEN)).isFalse();
        assertThat(TicketStateMachine.canTransition(TicketStatus.OPEN, TicketStatus.OPEN)).isFalse();
    }

    @Test
    void customTableFromWorkflowOverridesDefaults() {
        Map<TicketStatus, Set<TicketStatus>> custom = new java.util.EnumMap<>(TicketStatus.class);
        for (TicketStatus s : TicketStatus.values()) custom.put(s, EnumSet.noneOf(TicketStatus.class));
        custom.get(TicketStatus.NEW).add(TicketStatus.RESOLVED);
        assertThat(TicketStateMachine.canTransition(custom, TicketStatus.NEW, TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStateMachine.canTransition(custom, TicketStatus.NEW, TicketStatus.OPEN)).isFalse();
    }

    @Test
    void doneAndPausingSets() {
        assertThat(TicketStatus.DONE).containsExactlyInAnyOrder(TicketStatus.RESOLVED, TicketStatus.CLOSED);
        assertThat(TicketStatus.SLA_PAUSING).containsExactlyInAnyOrder(TicketStatus.PENDING, TicketStatus.ON_HOLD);
    }
}
