package com.example.cx.notification;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Standalone notification-service (port 8082). Deliberately NOT @SpringBootApplication: no implicit package scan.
 *
 * <p>Its configuration files are named {@code notification-service.yml} / {@code notification-service-docker.yml} (not
 * {@code application*.yml}) so that this module can also be a library jar inside core-service without its config files
 * competing with the host application's {@code application*.yml}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(NotificationConfiguration.class)
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NotificationServiceApplication.class);
        app.setDefaultProperties(Map.of("spring.config.name", "notification-service"));
        app.run(args);
    }
}
