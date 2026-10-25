package com.example.cx.core.identity;

import com.example.cx.core.identity.IdentityDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    /** Bounded list (max 500, default 200) for assignment pickers. Inactive users only visible to ADMIN. */
    @GetMapping
    @PreAuthorize("hasRole('AGENT')")
    public List<UserDto> list(@RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "200") int limit,
                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        boolean admin = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return users.list(q, includeInactive && admin, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto create(@Valid @RequestBody CreateUserRequest r) { return users.create(r); }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest r) { return users.update(id, r); }
}
