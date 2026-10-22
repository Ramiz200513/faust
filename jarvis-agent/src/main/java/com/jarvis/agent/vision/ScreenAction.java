package com.jarvis.agent.vision;

import java.util.List;

/**
 * Решение модели на один шаг пиксельного цикла (scope = SCREEN). Формат
 * ответа LLM (строго JSON); x, y — координаты в пикселях КАРТИНКИ, которую
 * отправили модели (0,0 — левый верхний угол):
 *
 *   {"action":"click","x":N,"y":M}   навести курсор на (x,y) и кликнуть
 *   {"action":"type","text":"..."}   ввести текст в то, что сейчас в фокусе
 *   {"action":"scroll","dy":N}       прокрутить колесом мыши (dy>0 — вниз)
 *   {"action":"open_app","name":"..."} открыть программу через меню «Пуск» (точно, без кликов)
 *   {"action":"key","keys":"..."}    нажать клавишу или сочетание: enter, esc, ctrl+f...
 *   {"action":"ask","question":"...","options":["..."]}  спросить человека на телефоне
 *   {"action":"done","reason":"..."} цель достигнута ЛИБО недостижима
 *
 * Для open_app, key и ask отдельных полей не заводим: название программы,
 * сочетание клавиш и текст вопроса кладутся в то же поле text, что и текст
 * для type, — у одного действия всегда заполнено что-то одно.
 *
 * В отличие от {@link AgentAction} (DOM-режим, index элемента в списке),
 * здесь модель называет координаты "на глаз" по картинке — это менее точно
 * и дороже (картинка вместо короткого списка элементов), но зато работает
 * в ЛЮБОЙ программе, а не только в браузере.
 */
public record ScreenAction(String action, Integer x, Integer y, String text, Integer dy, String reason,
                           List<String> options, Boolean success) {

    /** Прежний конструктор без options и success — для заглушек при ошибке. */
    public ScreenAction(String action, Integer x, Integer y, String text, Integer dy, String reason) {
        this(action, x, y, text, dy, reason, null, null);
    }

    public boolean isDone() {
        return "done".equalsIgnoreCase(action);
    }

    /** Для done: достигнута ли цель — см. {@link DoneVerdict}. */
    public boolean succeeded() {
        return DoneVerdict.succeeded(success, reason);
    }

    public boolean isAsk() {
        return "ask".equalsIgnoreCase(action);
    }

    @Override
    public String toString() {
        return switch (action == null ? "" : action.toLowerCase()) {
            case "click"  -> "click(" + x + ", " + y + ")";
            case "type"   -> "type(\"" + text + "\")";
            case "scroll" -> "scroll(" + (dy == null ? 0 : dy) + ")";
            case "open_app" -> "open_app(\"" + text + "\")";
            case "key"    -> "key(" + text + ")";
            case "ask"    -> "ask(\"" + text + "\"" + AgentAction.optionsSuffix(options) + ")";
            case "find_file"  -> "find_file(\"" + text + "\")";
            case "paste_file" -> "paste_file(\"" + text + "\")";
            case "extract_archive" -> "extract_archive(\"" + (text == null ? "" : text.replace("|", "\" -> \"")) + "\")";
            case "done"   -> "done" + (reason == null ? "" : ": " + reason);
            default       -> "unknown(" + action + ")";
        };
    }
}
