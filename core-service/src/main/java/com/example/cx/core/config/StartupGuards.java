package com.example.cx.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fail fast on insecure configuration in the production-like profile. */
@Component
@Profile("docker")
public class StartupGuards {
    private final CxProperties props;

    public StartupGuards(CxProperties props) { this.props = props; }

    @PostConstruct
    void check() {
        String secret = props.security().jwt().secret();
        if (secret == null || secret.equals(CxProperties.DEV_JWT_SECRET) || secret.startsWith("dev-only")) {
            throw new IllegalStateException("cx.security.jwt.secret (CX_JWT_SECRET) must be set to a real secret in the docker profile; "
                    + "the development default is refused");
        }
    }
}
