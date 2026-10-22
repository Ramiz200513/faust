package com.jarvis.relay.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Единственная причина этого класса — BCrypt для хэширования постоянных
 * токенов устройств перед сохранением в БД. Полноценный spring-security
 * (фильтры, логин-формы и т.д.) тут не нужен, поэтому подключена только
 * лёгкая библиотека spring-security-crypto.
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
