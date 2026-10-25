package com.example.cx.core.sla;

import com.example.cx.core.sla.SlaService.SlaPolicyDto;
import com.example.cx.core.sla.SlaService.SlaPolicyRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read: MANAGER+, write: ADMIN. Business hours themselves come from tenant settings. */
@RestController
@RequestMapping("/api/v1/sla-policies")
public class SlaPolicyController {
    private final SlaService sla;

    public SlaPolicyController(SlaService sla) { this.sla = sla; }

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public List<SlaPolicyDto> list() { return sla.list(); }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SlaPolicyDto update(@PathVariable UUID id, @Valid @RequestBody SlaPolicyRequest r) { return sla.update(id, r); }
}
