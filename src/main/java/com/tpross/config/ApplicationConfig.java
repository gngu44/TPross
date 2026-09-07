package com.tpross.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(AccountProperties.class)
public class ApplicationConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(encodingId,
                Map.of(encodingId, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
