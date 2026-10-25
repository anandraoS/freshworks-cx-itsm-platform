package com.example.cx.notification.security;

import java.util.List;
import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID tenantId, List<String> roles, String email, String jti) {}
