package com.example.cx.core.sla;

import com.example.cx.core.tenancy.TenantOwned;
import com.example.cx.core.tickets.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "sla_policy")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class SlaPolicy extends TenantOwned {
    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;
    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;
    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;
    @Column(name = "escalation_after_percent", nullable = false, length = 60)
    private String escalationAfterPercentCsv = "75,100";
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public List<Integer> getEscalationAfterPercent() {
        return Arrays.stream(escalationAfterPercentCsv.split(",")).filter(s -> !s.isBlank()).map(Integer::parseInt).toList();
    }

    public void setEscalationAfterPercent(List<Integer> pct) {
        this.escalationAfterPercentCsv = String.join(",", pct.stream().map(String::valueOf).toList());
    }
}
