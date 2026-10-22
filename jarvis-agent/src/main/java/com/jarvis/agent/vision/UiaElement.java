package com.jarvis.agent.vision;

/**
 * Один элемент управления окна обычной программы, каким его видит агент в
 * режиме UI Automation.
 *
 * <p>Это прямой аналог {@link PageElement} из браузерного режима. Смысл тот же:
 * вместо картинки экрана модель получает короткий пронумерованный список того,
 * что есть в окне, и отвечает номером. Разница только в источнике данных —
 * там DOM страницы, здесь дерево элементов, которое отдаёт сама Windows.
 *
 * @param index       номер в ТЕКУЩЕМ снимке (после каждого действия нумерация пересобирается)
 * @param controlType тип элемента по версии Windows: button, edit, list item, menu item...
 * @param name        подпись элемента («Поиск сообщений», «Сохранить», «Загрузки»)
 * @param x           левый край элемента в пикселях экрана
 * @param y           верхний край
 * @param width       ширина
 * @param height      высота
 * @param isPassword  Windows пометила поле как поле для пароля — сюда агент не вводит ничего
 * @param isEnabled   элемент доступен (серую кнопку нажимать бессмысленно)
 * @param value       что уже набрано в поле — чтобы агент не дописывал свой текст к чужому
 * @param hasFocus    на этом элементе сейчас курсор ввода: сюда попадёт набранный текст
 */
public record UiaElement(
        int index,
        String controlType,
        String name,
        int x, int y, int width, int height,
        boolean isPassword,
        boolean isEnabled,
        String value,
        boolean hasFocus
) {

    /** Центр элемента по горизонтали — сюда агент ведёт мышь. */
    public int centerX() {
        return x + width / 2;
    }

    /** Центр элемента по вертикали. */
    public int centerY() {
        return y + height / 2;
    }

    /** Площадь в пикселях — нужна, чтобы отличать кнопки от контейнеров во весь экран. */
    public long area() {
        return (long) width * height;
    }

    /**
     * Строка для промпта. Формат намеренно повторяет {@link PageElement#toString()}:
     * {@code [5] <button> "Поиск сообщений"} — модель уже умеет отвечать номером
     * из такого списка в браузерном режиме.
     */
    @Override
    public String toString() {
        String flags = "";
        if (value != null && !value.isBlank()) {
            // Длинное значение обрезаем: список для модели должен оставаться коротким.
            String v = value.length() > 60 ? value.substring(0, 60) + "..." : value;
            flags += " {сейчас введено: \"" + v + "\"}";
        }
        if (hasFocus) flags += " {курсор ввода здесь}";
        if (!isEnabled) flags += " {недоступен}";
        if (isPassword) flags += " {поле пароля}";
        return "[" + index + "] <" + controlType + "> \"" + name + "\"" + flags;
    }
}
