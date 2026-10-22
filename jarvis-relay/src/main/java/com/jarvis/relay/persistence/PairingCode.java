package com.jarvis.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Одноразовый код паринга. Админ создаёт его через POST /api/pairing-codes,
 * человек вручную вводит код в агента/телефон при первом подключении —
 * вместо общего токена. Код живёт ограниченное время и гасится после первого
 * успешного использования.
 */
@Entity
@Table(name = "pairing_codes")
public class PairingCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 6)
    private String code;

    /** Необязательная метка ("рабочий ноутбук") — станет Device.name после паринга. */
    @Column(name = "label")
    private String label;

    /**
     * Для какого ПК выписан код. null — код для привязки самого устройства
     * (агента) через Auth. Не null — код для телефона: по нему телефон
     * через PairRequest получает доступ именно к этому ПК.
     */
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PairingCode() {
        // для Hibernate
    }

    public PairingCode(String code, String label, String agentId, Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.label = label;
        this.agentId = agentId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    /** Код ещё не использован и срок его действия не истёк. */
    public boolean isValidNow(Instant now) {
        return !used && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        this.used = true;
        this.usedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getAgentId() {
        return agentId;
    }

    /** Код выписан для телефона (привязка к конкретному ПК), а не для агента. */
    public boolean isForPhone() {
        return agentId != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
