package com.jarvis.agent;

import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.vision.CancelSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Встреча двух потоков: задача ждёт ответа, поток сокета его приносит.
 * Здесь «сокет» — просто список отправленных сообщений, а ответ приходит
 * из отдельного потока, как в жизни.
 */
class PhoneQuestionsTest {

    private final List<JarvisMessage> sent = new CopyOnWriteArrayList<>();
    private final PhoneQuestions questions = new PhoneQuestions(sent::add);

    /** Дождаться, пока вопрос уйдёт «на телефон», и вернуть его. */
    private JarvisMessage.AgentQuestion awaitQuestion() throws InterruptedException {
        for (int i = 0; i < 100 && sent.isEmpty(); i++) Thread.sleep(10);
        assertFalse(sent.isEmpty(), "вопрос так и не был отправлен");
        return (JarvisMessage.AgentQuestion) sent.get(0);
    }

    @Test
    void answerFromSocketThread_wakesTheTask() throws Exception {
        Thread phone = new Thread(() -> {
            try {
                JarvisMessage.AgentQuestion q = awaitQuestion();
                assertTrue(questions.answer(q.questionId(), "  Да "));
            } catch (InterruptedException ignored) { }
        });
        phone.start();

        String answer = questions.ask("task-1", "Отправить?", List.of("Да", "Нет"), 5, CancelSignal.NONE);
        phone.join();

        assertEquals("Да", answer, "ответ приходит без лишних пробелов");
        JarvisMessage.AgentQuestion q = (JarvisMessage.AgentQuestion) sent.get(0);
        assertEquals("task-1", q.requestId(), "вопрос привязан к задаче");
        assertEquals(List.of("Да", "Нет"), q.options());
        assertEquals(5, q.timeoutSeconds());
    }

    @Test
    void unknownQuestion_isRejected() {
        assertFalse(questions.answer("нет-такого", "Да"));
    }

    @Test
    void timeout_returnsNull_andLateAnswerIsRejected() {
        long t0 = System.currentTimeMillis();
        String answer = questions.ask("task-1", "Кому?", List.of(), 1, CancelSignal.NONE);
        long dt = System.currentTimeMillis() - t0;

        assertNull(answer);
        assertTrue(dt >= 1000 && dt < 3000, "ждали " + dt + " мс");
        String id = ((JarvisMessage.AgentQuestion) sent.get(0)).questionId();
        assertFalse(questions.answer(id, "поздно"), "после таймаута ответ не принимается");
    }

    @Test
    void cancel_stopsWaitingQuickly() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            cancelled.set(true);
        }).start();

        long t0 = System.currentTimeMillis();
        String answer = questions.ask("task-1", "Кому?", List.of(), 60, cancelled::get);
        long dt = System.currentTimeMillis() - t0;

        assertNull(answer);
        assertTrue(dt < 2000, "«Стоп» должен прерывать ожидание, а не ждать 60 с; ждали " + dt + " мс");
    }

    @Test
    void nullOptions_goOutAsEmptyList() {
        // Телефон (kotlinx.serialization) падает на "options":null — record обязан это исправить.
        JarvisMessage.AgentQuestion q = new JarvisMessage.AgentQuestion("t", "q", "Кому?", null, null);
        assertEquals(List.of(), q.options());
    }
}
