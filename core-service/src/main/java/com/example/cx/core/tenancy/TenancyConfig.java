package com.example.cx.core.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TenancyConfig {

    /** Replaces Boot's default JpaTransactionManager. RLS session variable is only set under the docker profile. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf, Environment env,
                                                  @Value("${cx.tenancy.pg-rls:false}") boolean pgRlsFlag) {
        boolean rls = pgRlsFlag || env.acceptsProfiles(Profiles.of("docker"));
        return new TenantAwareJpaTransactionManager(emf, rls);
    }
}
