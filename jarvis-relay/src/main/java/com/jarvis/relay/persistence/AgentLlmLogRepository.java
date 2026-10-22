package com.jarvis.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentLlmLogRepository extends JpaRepository<AgentLlmLog, Long> {
}
