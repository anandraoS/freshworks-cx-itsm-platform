package com.example.cx.notification.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration("cxNotificationWebConfig")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class NotificationWebConfig implements WebMvcConfigurer {
    private final JwtAuthInterceptor interceptor;

    public NotificationWebConfig(JwtAuthInterceptor interceptor) { this.interceptor = interceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/notifications", "/api/v1/notifications/**");
    }
}
