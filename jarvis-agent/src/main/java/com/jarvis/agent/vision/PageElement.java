package com.jarvis.agent.vision;

/**
 * Один интерактивный элемент страницы, каким его видит агент.
 * Список таких элементов — это «глаза» агента: вместо картинки мы отдаём
 * модели структурированный текст (DOM-режим, самый надёжный путь).
 */
public record PageElement(
        int index,    // порядковый номер в ТЕКУЩЕМ снимке страницы (после действия нумерация пересобирается)
        String tag,   // html-тег: a, button, input, textarea...
        String role,  // role или type, если заданы: search, submit, button, tab...
        String text,  // видимая подпись: innerText / value / aria-label / placeholder / title
        int x, int y  // центр элемента в координатах окна — нужно только для трейса/отладки
) {
    @Override
    public String toString() {
        String r = (role == null || role.isBlank()) ? "" : " {" + role + "}";
        return "[" + index + "] <" + tag + ">" + r + " \"" + text + "\"";
    }
}
