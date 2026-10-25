package com.example.cx.core.workflow;

import com.example.cx.core.workflow.WorkflowService.ValidationResult;
import com.example.cx.core.workflow.WorkflowService.WorkflowDto;
import com.example.cx.core.workflow.WorkflowService.WorkflowRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Read: MANAGER and above. Write (create / validate / activate): ADMIN. */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) { this.workflows = workflows; }

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public List<WorkflowDto> list() { return workflows.list(); }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public WorkflowDto get(@PathVariable UUID id) { return workflows.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public WorkflowDto create(@Valid @RequestBody WorkflowRequest r) { return workflows.create(r); }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ValidationResult validate(@Valid @RequestBody WorkflowRequest r) { return workflows.validate(r); }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public WorkflowDto activate(@PathVariable UUID id) { return workflows.activate(id); }
}
