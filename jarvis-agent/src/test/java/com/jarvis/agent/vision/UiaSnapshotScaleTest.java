package com.jarvis.agent.vision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Перевод координат UI Automation (физические пиксели) в координаты Java
 * (логические) при масштабе Windows 125%. Ошибка, которую ловит этот тест,
 * найдена на живом прогоне 30.09: клики уезжали в точку ×1.25 от нужной.
 */
class UiaSnapshotScaleTest {

    @Test
    void physicalToLogical_at125() {
        assertArrayEquals(new int[]{800, 80, 80, 32}, UiaSnapshot.toLogical(new int[]{1000, 100, 100, 40}, 1.25));
        int[] same = {1, 2, 3, 4};
        assertSame(same, UiaSnapshot.toLogical(same, 1.0), "при 100% ничего не меняется");
    }

    @Test
    void buttonOnTheRightEdge_isKept_andGetsLogicalCenter() throws Exception {
        // Кнопка «Отправить» у правого края экрана 1920×1080 (физически).
        // Раньше её физический x=1780 сравнивался с логической шириной 1536,
        // и кнопка молча выпадала из списка для модели.
        String json = """
                {"windowTitle":"Лера – Telegram","windowRect":"0,0,1920,1080","elements":[
                  {"controlType":"button","name":"Отправить","rect":"1760,1000,40,40",
                   "isPassword":false,"isEnabled":true,"isOffscreen":false,"value":"","hasFocus":false}]}
                """;

        UiaSnapshot.Result r = new UiaSnapshot().parse(json, 0, 1.25);

        List<UiaElement> els = r.elements();
        assertEquals(1, els.size(), "кнопка у правого края не должна пропадать");
        UiaElement send = els.get(0);
        // Центр физически (1780, 1020) -> логически (1424, 816): туда и кликнет Robot.
        assertEquals(1424, send.centerX());
        assertEquals(816, send.centerY());
    }
}
