package com.jarvis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Разбор клавиш человека: символы НЕ передаются (это был бы кейлоггер), служебные клавиши и
 * сочетания с Ctrl / Alt / Win — по имени (с 23.11; раньше Alt и Win отбрасывались, и наставник
 * не мог засчитать шаг «нажмите Win + плюс»). Запись показом видит всё как раньше — legacyCombo.
 */
class HumanInputTest {

    private static HumanInput.Event key(int vk, boolean ctrl, boolean alt, boolean shift, boolean win) {
        return HumanInput.classifyKey(vk, ctrl, alt, shift, win, 1);
    }

    @Test
    void keys() {
        assertEquals(new HumanInput.Key("enter", 1), HumanInput.classifyKey(0x0D, false, false, 1));
        assertEquals(new HumanInput.Key("ctrl+s", 1), HumanInput.classifyKey(0x53, true, false, 1));
        assertEquals(new HumanInput.Typing(1), HumanInput.classifyKey(0x41, false, false, 1),
                "буква — только факт ввода, без самой буквы");
        assertNull(HumanInput.classifyKey(0x5B, false, false, 1), "сама Win — не событие");
        assertNull(HumanInput.classifyKey(0x11, true, false, 1), "сам Ctrl — не событие");
    }

    @Test
    void shortcutsWithWinAltShift_areNamed() {
        // Живой урок 19.11: экранная лупа — Win + плюс.
        assertEquals(new HumanInput.Key("win+plus", 1), key(0xBB, false, false, false, true));
        assertEquals(new HumanInput.Key("win+plus", 1), key(0x6B, false, false, false, true), "плюс на цифровом блоке");
        assertEquals(new HumanInput.Key("win+minus", 1), key(0xBD, false, false, false, true));
        assertEquals(new HumanInput.Key("win+d", 1), key(0x44, false, false, false, true));
        assertEquals(new HumanInput.Key("alt+tab", 1), key(0x09, false, true, false, false));
        assertEquals(new HumanInput.Key("alt+f4", 1), key(0x73, false, true, false, false));
        assertEquals(new HumanInput.Key("ctrl+1", 1), key(0x31, true, false, false, false));
        assertEquals(new HumanInput.Key("ctrl+shift+s", 1), key(0x53, true, false, true, false), "порядок: ctrl, alt, shift, win");
        assertEquals(new HumanInput.Key("shift+tab", 1), key(0x09, false, false, true, false));
    }

    @Test
    void textStaysText() {
        // Shift+буква — заглавная буква, то есть текст: только «печатает».
        assertEquals(new HumanInput.Typing(1), key(0x41, false, false, true, false));
        // «+» без модификатора — тоже текст.
        assertEquals(new HumanInput.Typing(1), key(0xBB, false, false, false, false));
    }

    @Test
    void demoRecording_seesCombosAsBefore() {
        // Запись показом не меняется: Win/Alt не записывались, Shift не учитывался, из Ctrl — Ctrl+буква.
        assertNull(HumanInput.legacyCombo("win+plus"));
        assertNull(HumanInput.legacyCombo("alt+tab"));
        assertNull(HumanInput.legacyCombo("ctrl+1"));
        assertEquals("ctrl+s", HumanInput.legacyCombo("ctrl+shift+s"));
        assertEquals("tab", HumanInput.legacyCombo("shift+tab"));
        assertEquals("enter", HumanInput.legacyCombo("enter"));
        assertEquals("ctrl+v", HumanInput.legacyCombo("ctrl+v"));
    }
}
