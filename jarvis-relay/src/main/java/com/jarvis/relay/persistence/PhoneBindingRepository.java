package com.jarvis.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhoneBindingRepository extends JpaRepository<PhoneBinding, Long> {

    /** Spring Data сам строит SQL по имени метода: where agent_id = ? */
    List<PhoneBinding> findByAgentId(String agentId);
}
