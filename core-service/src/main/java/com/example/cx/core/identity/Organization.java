package com.example.cx.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The tenant. Its id IS the tenantId carried in the JWT ("tid"). Not tenant-filtered itself: login resolves it by
 * slug before any tenant is known. It holds the tenant settings (timezone / business hours / integrations).
 */
@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class Organization {
    @Id
    private UUID id;
    @Column(nullable = false, length = 40, unique = true)
    private String slug;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 60)
    private String timezone = "UTC";
    @Column(name = "business_hours_start", nullable = false, length = 5)
    private String businessHoursStart = "09:00";
    @Column(name = "business_hours_end", nullable = false, length = 5)
    private String businessHoursEnd = "17:00";
    @Column(name = "business_days", nullable = false, length = 40)
    private String businessDays = "MON,TUE,WED,THU,FRI";
    @Column(name = "default_language", nullable = false, length = 10)
    private String defaultLanguage = "en";
    @Column(name = "email_provider", length = 40)
    private String emailProvider;
    @Column(name = "slack_webhook_url", length = 300)
    private String slackWebhookUrl;
    @Column(name = "ai_provider", length = 40)
    private String aiProvider;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
