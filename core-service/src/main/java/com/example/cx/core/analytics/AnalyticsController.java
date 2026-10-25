package com.example.cx.core.analytics;

import com.example.cx.core.analytics.AnalyticsService.Dashboard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) { this.analytics = analytics; }

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('MANAGER')")
    public Dashboard dashboard(@RequestParam(defaultValue = "30") int days) { return analytics.dashboard(days); }
}
