package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Отказ и «сделал половину» больше не считаются успехом. */
class DoneVerdictTest {

    private final GeminiVisionAgent brain = new GeminiVisionAgent(null);

    @Test
    void explicitFlag_wins() throws Exception {
        assertFalse(brain.parseAction("{\"action\":\"done\",\"success\":false,\"reason\":\"курс найден, но в Блокнот записать не могу\"}").succeeded());
        assertTrue(brain.parseAction("{\"action\":\"done\",\"success\":true,\"reason\":\"файл отправлен\"}").succeeded());
    }

    @Test
    void withoutFlag_refusalIsNotSuccess() throws Exception {
        // Ровно этот ответ на прогоне 30.09 записался как «цель достигнута».
        assertFalse(brain.parseAction("{\"action\":\"done\",\"reason\":\"нужен ввод пароля/платёжных данных — отказываюсь\"}").succeeded());
        assertFalse(brain.parseScreenAction("{\"action\":\"done\",\"reason\":\"цель недостижима\"}").succeeded());
        assertTrue(brain.parseAction("{\"action\":\"done\",\"reason\":\"видео открыто\"}").succeeded());
    }

    @Test
    void llmUnavailable_isNeverSuccess() {
        assertFalse(DoneVerdict.succeeded(true, "LLM недоступна: HTTP 503"));
        assertFalse(DoneVerdict.succeeded(null, "LLM недоступна: HTTP 503"));
    }
}
