package com.jarvis.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentLessonRepository extends JpaRepository<IntentLesson, Long> {

    /** Самый свежий подтверждённый урок по этой фразе. */
    Optional<IntentLesson> findFirstByPhraseNormAndConfirmedAtIsNotNullOrderByConfirmedAtDesc(String phraseNorm);

    List<IntentLesson> findByRequestId(String requestId);

    List<IntentLesson> findByConfirmedAtIsNotNullOrderByConfirmedAtAsc();

    long countByConfirmedAtIsNotNull();
}
