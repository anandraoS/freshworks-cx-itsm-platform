package com.example.cx.core.identity;

import com.example.cx.core.identity.IdentityDtos.TenantSettingsDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenant/settings")
public class TenantSettingsController {
    private final TenantSettingsService settings;

    public TenantSettingsController(TenantSettingsService settings) { this.settings = settings; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public TenantSettingsDto get() { return settings.get(); }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public TenantSettingsDto put(@Valid @RequestBody TenantSettingsDto r) { return settings.update(r); }
}
