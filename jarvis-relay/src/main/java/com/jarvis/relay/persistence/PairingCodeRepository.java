package com.jarvis.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PairingCodeRepository extends JpaRepository<PairingCode, Long> {

    Optional<PairingCode> findByCode(String code);

    boolean existsByCode(String code);
}
