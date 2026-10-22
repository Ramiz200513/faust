package com.jarvis.agent.vision;

import java.util.List;

/**
 * «Мозг» computer-use агента: на вход — цель и текущее состояние страницы,
 * на выход — следующее действие.
 *
 * Вынесено за интерфейс намеренно: LLM-провайдера (Gemini, OpenAI, локальная
 * модель) можно заменить, не трогая цикл {@link BrowserAgent}. Это тот же
 * приём, что и с IntentParser на релее.
 */
public interface VisionAgent {

    /**
     * @param goal     цель на естественном языке («найди на youtube видео про ... и открой первое»)
     * @param elements интерактивные элементы текущего экрана (нумерация — из этого же снимка)
     * @param history  что уже делали (строки трейса) — чтобы модель видела прогресс и не зацикливалась
     * @return следующее действие; при любой ошибке реализация обязана вернуть done, а не бросать исключение
     */
    AgentAction decideNext(String goal, List<PageElement> elements, List<String> history);

    /**
     * Пиксельный режим (scope = SCREEN): вместо списка DOM-элементов модель
     * получает картинку всего экрана и отвечает координатами. Нужен для
     * управления ЛЮБОЙ программой, а не только браузером — платим за это
     * меньшей точностью и более дорогим (мультимодальным) запросом.
     *
     * default-реализация не поддерживает пиксельный режим — так добавление
     * этого метода не ломает уже существующие реализации VisionAgent.
     *
     * @param goal      цель на естественном языке
     * @param jpegImage скриншот экрана, уже уменьшенный и сжатый в JPEG
     * @param imgWidth  ширина картинки в пикселях — координаты действия в этой же системе координат
     * @param imgHeight высота картинки в пикселях
     * @param history   что уже делали (строки трейса)
     * @return следующее действие; при любой ошибке реализация обязана вернуть done, а не бросать исключение
     */
    default ScreenAction decideNextOnScreen(String goal, byte[] jpegImage, int imgWidth, int imgHeight, List<String> history) {
        throw new UnsupportedOperationException("этот VisionAgent не поддерживает пиксельный режим (scope=SCREEN)");
    }

    /**
     * Режим UI Automation: то же, что {@link #decideNext}, но для окна обычной
     * программы, а не страницы браузера. Модель получает список элементов,
     * который отдала сама Windows, и отвечает номером — картинка не нужна.
     *
     * <p>Зачем отдельный метод, а не decideNext: набор доступных действий здесь
     * другой (нет navigate по адресу, зато есть open_app и нажатие клавиш), и
     * промпт должен объяснять модели, что она работает с окном программы.
     *
     * @param windowTitle заголовок активного окна — модели важно понимать, где она находится
     * @param elements    элементы окна из текущего снимка
     * @param history     что уже делали (строки трейса)
     * @return следующее действие; при любой ошибке реализация обязана вернуть done, а не бросать исключение
     */
    default AgentAction decideNextInWindow(String goal, String windowTitle,
                                           List<UiaElement> elements, List<String> history) {
        throw new UnsupportedOperationException("этот VisionAgent не поддерживает режим UI Automation");
    }
}
