package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Фильтр паролей и платёжных данных: после сужения «карт/card/pin» до целых
 * слов он должен по-прежнему ловить опасное и перестать ловить безобидное.
 */
class SensitiveInputGuardTest {

    @Test
    void stillCatchesTheDangerous() {
        assertTrue(SensitiveInputGuard.mentionsSensitive("введи пароль от почты"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("оплати заказ"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("номер карты 4276 1600 0000 0000"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("данные карты"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("введи CVV"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("enter your card"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("PIN"));
        assertTrue(SensitiveInputGuard.mentionsSensitive("привяжи карту"));
    }

    @Test
    void noLongerBlocksTheHarmless() {
        // Именно такие подзадачи строит планировщик — раньше все три отказывались стартовать.
        assertFalse(SensitiveInputGuard.mentionsSensitive("отправь картинку Лере"));
        assertFalse(SensitiveInputGuard.mentionsSensitive("открой pinterest"));
        assertFalse(SensitiveInputGuard.mentionsSensitive("найди shopping list"));
    }
}
