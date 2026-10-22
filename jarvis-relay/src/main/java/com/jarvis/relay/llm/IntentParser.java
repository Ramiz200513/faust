package com.jarvis.relay.llm;

import com.jarvis.relay.protocol.Command;

/**
 * Turns a raw spoken phrase into a concrete Command. Behind this interface
 * sits either the stub (regex, free) or a real LLM call (Gemini/GPT by API
 * key). Swapping providers = swapping the @Component that implements this.
 */
public interface IntentParser {
    /** @return a concrete command, or null if the phrase wasn't understood. */
    Command parse(String phrase);

    /**
     * То же, но с номером запроса телефона: связке разборщиков он нужен, чтобы
     * привязать «урок» для своей модели к результату выполнения (IntentLessons).
     * Остальным разборщикам номер не нужен.
     */
    default Command parse(String phrase, String requestId) {
        return parse(phrase);
    }
}
