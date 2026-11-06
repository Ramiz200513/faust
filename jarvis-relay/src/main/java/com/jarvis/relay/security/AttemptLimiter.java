package com.jarvis.relay.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сколько неудачных попыток разрешено за окно времени — защита от перебора.
 *
 * Зачем. Код привязки — 6 цифр, миллион вариантов, живёт 10 минут. Пока релей
 * был только в домашней сети, перебирать было некому. В интернете бот за
 * 10 минут успеет перепробовать сотни тысяч кодов и привязать к себе чужой ПК.
 * С лимитом в 100 неудач на 10 минут шанс угадать хоть один живой код —
 * порядка 1 к 10 000, а с лимитом по адресу — ещё меньше.
 *
 * Считаются только НЕУДАЧИ: человек, который ввёл код правильно, лимит не тратит.
 * Ключ — что угодно: "ip:1.2.3.4" или общий "pairing".
 *
 * Время берётся из {@link Clock} — чтобы тест мог «промотать» окно, не засыпая.
 */
public final class AttemptLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public AttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public AttemptLimiter(int maxFailures, Duration window) {
        this(maxFailures, window, Clock.systemUTC());
    }

    /** Исчерпан ли лимит неудач по ключу — тогда попытку даже не проверяем. */
    public boolean isBlocked(String key) {
        Deque<Long> times = failures.get(key);
        if (times == null) return false;
        synchronized (times) {
            dropOld(times);
            return times.size() >= maxFailures;
        }
    }

    /** Запомнить неудачу по ключу. */
    public void recordFailure(String key) {
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            dropOld(times);
            times.addLast(clock.millis());
        }
        // Ключей (адресов) может набраться много — пустые иногда выбрасываем,
        // чтобы память не росла от каждого случайного сканера.
        if (failures.size() > 10_000) failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                dropOld(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }

    private void dropOld(Deque<Long> times) {
        long border = clock.millis() - window.toMillis();
        while (!times.isEmpty() && times.peekFirst() < border) times.pollFirst();
    }
}
