package com.jarvis.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Привязка «телефон -> конкретный ПК». Появляется, когда телефон погасил
 * код через PairRequest. У одного ПК может быть несколько телефонов, у
 * телефона — несколько ПК (на каждый своя строка и свой токен).
 *
 * Телефон потом входит так: Auth(deviceId = agentId, token = свой токен,
 * role = PHONE). Релей ищет строки по agentId и сверяет токен с хэшем.
 */
@Entity
@Table(name = "phone_bindings")
public class PhoneBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** id агента (строка в devices с role=AGENT), которым управляет телефон. */
    @Column(name = "agent_id", nullable = false, updatable = false)
    private String agentId;

    /** Метка кода ("phone") — чтобы в БД было видно, чей это телефон. */
    @Column(name = "name")
    private String name;

    /** BCrypt-хэш постоянного токена телефона. Сам токен не храним. */
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "paired_at", nullable = false, updatable = false)
    private Instant pairedAt;

    protected PhoneBinding() {
        // для Hibernate
    }

    public PhoneBinding(String agentId, String name, String tokenHash, Instant pairedAt) {
        this.agentId = agentId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.pairedAt = pairedAt;
    }

    public Long getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }
}
