package com.example.cx.core.tickets;

import com.example.cx.core.common.PageResponse;
import com.example.cx.core.tickets.TicketDtos.ActivityDto;
import com.example.cx.core.tickets.TicketDtos.AssignRequest;
import com.example.cx.core.tickets.TicketDtos.CommentDto;
import com.example.cx.core.tickets.TicketDtos.CommentRequest;
import com.example.cx.core.tickets.TicketDtos.CreateTicketRequest;
import com.example.cx.core.tickets.TicketDtos.TicketDto;
import com.example.cx.core.tickets.TicketDtos.TicketQuery;
import com.example.cx.core.tickets.TicketDtos.TransitionRequest;
import com.example.cx.core.tickets.TicketDtos.UpdateTicketRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService tickets;
    private final TicketCommandService commands;

    public TicketController(TicketService tickets, TicketCommandService commands) {
        this.tickets = tickets;
        this.commands = commands;
    }

    @GetMapping
    public PageResponse<TicketDto> list(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) TicketStatus status,
                                        @RequestParam(required = false) Priority priority,
                                        @RequestParam(required = false) UUID assigneeId,
                                        @RequestParam(required = false) UUID categoryId,
                                        @RequestParam(required = false) TicketType type,
                                        @RequestParam(required = false) SlaStatus slaStatus,
                                        @RequestParam(defaultValue = "false") boolean mine) {
        return tickets.search(new TicketQuery(q, status, priority, assigneeId, categoryId, type, slaStatus, mine), page, size, sort);
    }

    @PostMapping
    public ResponseEntity<TicketDto> create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                            @Valid @RequestBody CreateTicketRequest r) {
        TicketCommandService.Result res = commands.create(idempotencyKey, r);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.CREATED);
        if (res.replayed()) b.header("Idempotent-Replay", "true");
        return b.body(res.ticket());
    }

    @GetMapping("/{id}")
    public TicketDto get(@PathVariable UUID id) { return tickets.get(id); }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('AGENT')")
    public TicketDto update(@PathVariable UUID id, @Valid @RequestBody UpdateTicketRequest r) { return tickets.update(id, r); }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasRole('AGENT')")
    public TicketDto assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) { return tickets.assign(id, r); }

    /** Staff: any allowed transition. Customers: only reopen (enforced in the service). */
    @PostMapping("/{id}/transition")
    public TicketDto transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) { return tickets.transition(id, r); }

    @GetMapping("/{id}/comments")
    public List<CommentDto> comments(@PathVariable UUID id) { return tickets.listComments(id); }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentDto> addComment(@PathVariable UUID id, @Valid @RequestBody CommentRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tickets.addComment(id, r.body(), r.visibility(), false));
    }

    @GetMapping("/{id}/activity")
    public List<ActivityDto> activity(@PathVariable UUID id) { return tickets.activity(id); }
}
