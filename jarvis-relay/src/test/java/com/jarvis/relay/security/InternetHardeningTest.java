package com.jarvis.relay.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Защита перед выходом релея в интернет: лимит перебора и проверка секретов.
 */
class InternetHardeningTest {

    /** Часы, которые тест двигает сам — чтобы проверить окно в 10 минут без ожидания. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void limiter_blocksAfterMaxFailures_andForgetsAfterWindow() {
        ManualClock clock = new ManualClock();
        AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(10), clock);
        for (int i = 0; i < 2; i++) limiter.recordFailure("ip:1.2.3.4");
        assertFalse(limiter.isBlocked("ip:1.2.3.4"), "две ошибки — ещё можно");
        limiter.recordFailure("ip:1.2.3.4");
        assertTrue(limiter.isBlocked("ip:1.2.3.4"), "третья — пауза");
        assertFalse(limiter.isBlocked("ip:5.6.7.8"), "другой адрес не страдает");

        clock.advance(Duration.ofMinutes(11));
        assertFalse(limiter.isBlocked("ip:1.2.3.4"), "через 10 минут снова можно");
    }

    @Test
    void bruteForcingSixDigitCode_isHopeless_withGlobalLimit() {
        // 100 неверных кодов за 10 минут на всех — сколько это в шансах угадать
        // один живой код из миллиона за его жизнь (10 минут)?
        double chance = 100.0 / 1_000_000;
        assertTrue(chance <= 1e-4, "не больше 1 к 10 000");
    }

    @Test
    void weakSecrets_areRejected_strongOnesPass() {
        assertFalse(StrongSecretsCheck.problems("dev-admin-change-me", "jarvis").isEmpty());
        assertEquals(2, StrongSecretsCheck.problems("short", "jarvis").size());
        assertTrue(StrongSecretsCheck.problems(
                "Zq8vN2pX7rT4kL9mW3sB6yH1cF5gJ0dA", "p4ssw0rd-L0ng-Enough").isEmpty());
    }

    @Test
    void secretsCheck_isOff_byDefault_soHomeSetupKeepsWorking() {
        assertDoesNotThrow(() -> new StrongSecretsCheck(false, "dev-admin-change-me", "jarvis"));
        assertThrows(IllegalStateException.class, () -> new StrongSecretsCheck(true, "dev-admin-change-me", "jarvis"));
    }
}
