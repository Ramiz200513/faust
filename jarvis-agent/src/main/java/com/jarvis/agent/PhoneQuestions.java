package com.jarvis.agent;

import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.vision.CancelSignal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Доставка вопросов ИИ-агента на телефон и приём ответов.
 *
 * <h2>Два потока встречаются здесь</h2>
 * <ul>
 *   <li><b>поток задачи</b> (jarvis-agent-task) вызывает {@link #ask}: шлёт
 *       AgentQuestion и засыпает в ожидании;</li>
 *   <li><b>поток чтения сокета</b> получает команду answer_question и вызывает
 *       {@link #answer}: будит поток задачи, отдавая ему ответ.</li>
 * </ul>
 * Место встречи — {@link CompletableFuture} на каждый вопрос. Ключ — случайный
 * questionId: ответ на старый, уже брошенный вопрос (человек нажал кнопку через
 * пять минут) не попадёт по ошибке в следующий.
 *
 * <p>Ответить можно, только пока вопрос ждёт: после таймаута или отмены он
 * удаляется из {@link #pending}, и {@link #answer} вернёт false. Поэтому
 * команда answer_question сама по себе ничего на ПК сделать не может — это
 * важно, ведь она проходит через белый список релея как самостоятельная команда.
 */
final class PhoneQuestions {

    /** Ответ длиннее этого — не ответ, а что-то странное; обрезаем, чтобы не раздувать промпт. */
    private static final int MAX_ANSWER_LENGTH = 500;
    /**
     * Как часто, пока ждём, проверять отмену задачи. 300 мс — кнопка «Стоп»
     * на телефоне срабатывает практически сразу, а лишней нагрузки нет.
     */
    private static final long POLL_MS = 300;

    private final Consumer<JarvisMessage> sender;
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    PhoneQuestions(Consumer<JarvisMessage> sender) {
        this.sender = sender;
    }

    /**
     * Задать вопрос и ждать ответа. Вызывается из потока задачи.
     *
     * @param requestId  id задачи — телефон покажет вопрос в её ленте
     * @param timeoutSec сколько ждать
     * @return ответ или null (время вышло, задачу отменили)
     */
    String ask(String requestId, String question, List<String> options, int timeoutSec, CancelSignal cancel) {
        String questionId = UUID.randomUUID().toString();
        CompletableFuture<String> reply = new CompletableFuture<>();
        // Регистрируем ДО отправки: телефон может ответить быстрее, чем мы
        // дойдём до строки после sender.accept, — ответ не должен потеряться.
        pending.put(questionId, reply);
        try {
            sender.accept(new JarvisMessage.AgentQuestion(requestId, questionId, question, options, timeoutSec));
            long until = System.currentTimeMillis() + timeoutSec * 1000L;
            while (System.currentTimeMillis() < until) {
                if (cancel.isCancelled()) return null;
                try {
                    return reply.get(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillWaiting) {
                    // Ответа пока нет — проверим отмену и подождём ещё.
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null; // future никто не завершает с ошибкой, но компилятор требует ветку
        } finally {
            // Вопрос больше не ждёт: поздний ответ получит «вопрос неактуален».
            pending.remove(questionId);
        }
    }

    /**
     * Ответ с телефона. Вызывается из потока чтения сокета.
     *
     * @return true — ответ передан ждущей задаче; false — такого вопроса никто не ждёт
     */
    boolean answer(String questionId, String answer) {
        if (questionId == null || answer == null) return false;
        String a = answer.strip();
        if (a.isEmpty()) return false;
        if (a.length() > MAX_ANSWER_LENGTH) a = a.substring(0, MAX_ANSWER_LENGTH);
        CompletableFuture<String> reply = pending.get(questionId);
        return reply != null && reply.complete(a);
    }
}
