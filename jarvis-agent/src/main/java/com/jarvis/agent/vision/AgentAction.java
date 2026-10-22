package com.jarvis.agent.vision;

import java.util.List;

/**
 * Решение модели на один шаг цикла. Формат ответа LLM (строго JSON):
 *
 *   {"action":"click","index":N}       навести и кликнуть по элементу N из текущего снимка
 *   {"action":"type","text":"..."}     ввести текст в активное поле и нажать Enter
 *   {"action":"scroll"}                прокрутить страницу вниз
 *   {"action":"navigate","url":"..."}  перейти прямо по адресу (только http/https)
 *   {"action":"ask","question":"...","options":["..."]}  спросить человека на телефоне
 *   {"action":"done"}                  цель достигнута ЛИБО недостижима — цикл останавливается
 *
 * Поля — обёртки (Integer), потому что у разных действий заполнены разные поля,
 * остальные приходят null. index/text/amount/url опциональны; reason — необязательный
 * комментарий модели, удобен для трейса. У ask текст вопроса лежит в text (как
 * название программы у open_app), а варианты ответа — в options.
 */
public record AgentAction(String action, Integer index, String text, Integer amount, String url, String reason,
                          List<String> options, Boolean success) {

    /**
     * Прежний конструктор без options и success. Им пользуются все места, где
     * действие создаётся не из ответа модели (повтор из памяти, заглушки при
     * ошибке), — эти поля им не нужны, и переписывать их ради них незачем.
     */
    public AgentAction(String action, Integer index, String text, Integer amount, String url, String reason) {
        this(action, index, text, amount, url, reason, null, null);
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
            case "click"    -> "click(" + index + ")";
            case "type"     -> "type(\"" + text + "\")";
            case "scroll"   -> "scroll(" + (amount == null ? 600 : amount) + ")";
            case "navigate" -> "navigate(" + url + ")";
            // key и open_app появились вместе с режимом UIA. Без них тут
            // печаталось "unknown(key)" — и это не косметика: трейс уходит
            // модели как история её же действий. Прочитав "unknown", она
            // считала, что действие не сработало, и повторяла его снова.
            // В журнале одного прогона так потерялось 4 шага подряд.
            case "key"      -> "key(" + text + ")";
            case "open_app" -> "open_app(\"" + text + "\")";
            case "ask"      -> "ask(\"" + text + "\"" + optionsSuffix(options) + ")";
            // Без этих строк трейс показал бы «unknown(find_file)», и модель
            // решила бы, что действие не сработало (см. комментарий про key выше).
            case "read"       -> "read(\"" + (text == null ? "" : text) + "\")";
            case "find_file"  -> "find_file(\"" + text + "\")";
            case "paste_file" -> "paste_file(\"" + text + "\")";
            case "extract_archive" -> "extract_archive(\"" + (text == null ? "" : text.replace("|", "\" -> \"")) + "\")";
            case "done"     -> "done" + (reason == null ? "" : ": " + reason);
            default         -> "unknown(" + action + ")";
        };
    }

    /** Варианты ответа для трейса: ask("Отправить?" [Да / Нет]). Общий для AgentAction и ScreenAction. */
    static String optionsSuffix(List<String> options) {
        return options == null || options.isEmpty() ? "" : " [" + String.join(" / ", options) + "]";
    }
}
