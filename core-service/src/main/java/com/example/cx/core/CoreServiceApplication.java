package com.example.cx.core;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class) // no default in-memory user / generated password: auth is JWT only
@ConfigurationPropertiesScan
@EnableScheduling
public class CoreServiceApplication {
    public static void main(String[] args) {
        // Day-bucketed analytics and business-hours math assume the DB session/JVM run in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
