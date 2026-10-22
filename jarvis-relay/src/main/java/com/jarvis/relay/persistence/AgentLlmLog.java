package com.jarvis.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Одна строка на каждый вызов LLM-прокси агентом ({@code POST
 * /api/agent/llm/generate}). Нужна для будущих замеров (успешность,
 * задержка vision-агента) — см. дипломную часть про success rate.
 */
@Entity
@Table(name = "agent_llm_log")
public class AgentLlmLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, updatable = false)
    private String deviceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Column(name = "ok", nullable = false, updatable = false)
    private boolean ok;

    protected AgentLlmLog() {
        // для Hibernate
    }

    public AgentLlmLog(String deviceId, Instant occurredAt, long latencyMs, boolean ok) {
        this.deviceId = deviceId;
        this.occurredAt = occurredAt;
        this.latencyMs = latencyMs;
        this.ok = ok;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public boolean isOk() {
        return ok;
    }
}
