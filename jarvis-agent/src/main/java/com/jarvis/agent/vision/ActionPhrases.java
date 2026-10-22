package com.jarvis.agent.vision;

import java.util.Locale;

/**
 * Действие агента — человеческой фразой для экрана ПК и телефона.
 *
 * <p>Модель думает в терминах «click(12)», а зритель на защите должен видеть
 * «Нажимаю «Отправить»». Название элемента у нас есть (UIA/DOM отдают
 * подпись), поэтому фраза получается конкретной, а не «Нажимаю».
 */
public final class ActionPhrases {

    private static final int MAX = 48;

    private ActionPhrases() {}

    /**
     * @param action     click, type, key, open_app, ...
     * @param text       текст действия (что печатать, какую программу открыть, путь)
     * @param targetName подпись элемента, по которому кликают; null — без элемента
     */
    public static String describe(String action, String text, String targetName) {
        String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "click" -> targetName == null || targetName.isBlank() ? "Нажимаю" : "Нажимаю «" + cut(firstPart(targetName)) + "»";
            case "type" -> "Печатаю «" + cut(text) + "»";
            case "key" -> "Нажимаю клавиши " + (text == null ? "" : text);
            case "open_app" -> "Открываю " + cut(text);
            case "scroll" -> "Прокручиваю";
            case "navigate" -> "Перехожу на " + host(text);
            case "read" -> "Читаю страницу";
            case "find_file" -> "Ищу файл «" + cut(text) + "»";
            case "paste_file" -> "Прикрепляю файл " + cut(fileName(text));
            case "extract_archive" -> "Распаковываю архив " + cut(fileName(text == null ? null : text.split("\\|")[0]));
            case "ask" -> "Спрашиваю: " + cut(text);
            case "done" -> "Готово";
            default -> a;
        };
    }

    /** «Избранное, Ramiz, В сети, Закреплено, ...» -> «Избранное»: в подписи чата много лишнего. */
    static String firstPart(String name) {
        int comma = name.indexOf(',');
        return comma > 0 ? name.substring(0, comma).strip() : name.strip();
    }

    private static String fileName(String path) {
        if (path == null) return "";
        String p = path.strip();
        int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? cut(url) : h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "страницу";
        }
    }

    private static String cut(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= MAX ? t : t.substring(0, MAX - 1) + "…";
    }
}
