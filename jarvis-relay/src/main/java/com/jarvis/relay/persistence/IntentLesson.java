package com.jarvis.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * «Урок» для нашей модели: фраза, которую она не поняла, и команда, которую
 * вместо неё назвал Gemini.
 *
 * <p>Жизнь урока: создан как «предложенный» в момент разбора → стал
 * «подтверждённым», когда агент выполнил эту команду успешно (значит, Gemini
 * понял фразу верно). Только подтверждённые уроки (1) сразу отвечают на ту же
 * фразу без Gemini и (2) попадают в следующее дообучение модели.
 */
@Entity
@Table(name = "intent_lesson", indexes = {
        @Index(name = "idx_intent_lesson_phrase", columnList = "phrase_norm"),
        @Index(name = "idx_intent_lesson_request", columnList = "request_id")
})
public class IntentLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Фраза в нормальном виде (нижний регистр, без знаков) — по ней ищем повтор. */
    @Column(name = "phrase_norm", nullable = false, length = 400)
    private String phraseNorm;

    @Column(name = "phrase", nullable = false, length = 400)
    private String phrase;

    /** Команда Gemini в формате протокола (JSON). */
    @Column(name = "command_json", nullable = false, length = 2000)
    private String commandJson;

    /** Что ответила наша модель — чтобы потом разобрать, на чём она ошибается. */
    @Column(name = "local_answer", length = 500)
    private String localAnswer;

    /** Запрос, в котором появился урок: по нему придёт результат выполнения. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** null — урок ещё не подтверждён выполнением. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected IntentLesson() {
        // для Hibernate
    }

    public IntentLesson(String phraseNorm, String phrase, String commandJson, String localAnswer, String requestId) {
        this.phraseNorm = phraseNorm;
        this.phrase = phrase;
        this.commandJson = commandJson;
        this.localAnswer = localAnswer;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    public void confirm() {
        if (confirmedAt == null) confirmedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPhraseNorm() { return phraseNorm; }
    public String getPhrase() { return phrase; }
    public String getCommandJson() { return commandJson; }
    public String getLocalAnswer() { return localAnswer; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
