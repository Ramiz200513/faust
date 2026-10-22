package com.jarvis.agent.vision;

/**
 * Сообщает об одном завершённом шаге computer-use цикла — чтобы телефон мог
 * показать процесс работы агента в реальном времени, а не только итог.
 * screenshotJpeg может быть null (не удалось снять/сжать) — репортер обязан
 * пережить это молча, а не бросать исключение.
 */
public interface StepReporter {
    /**
     * targetX/targetY — точка, куда целится модель. null, если шаг без цели
     * (type/scroll/done) или координаты не удалось определить.
     *
     * <p>В какой системе координат они заданы, зависит от {@link #needsScreenshot()}:
     * <ul>
     *   <li>true (обычный случай) — в пикселях ТОГО ЖЕ screenshotJpeg;</li>
     *   <li>false (идёт живой стрим, картинки нет) — в РЕАЛЬНЫХ пикселях экрана.
     *       Пересчитать их под кадр стрима — забота самого репортера: только он
     *       знает, какого размера кадры сейчас уходят на телефон.</li>
     * </ul>
     */
    void onStep(int step, String description, byte[] screenshotJpeg, int width, int height,
                Integer targetX, Integer targetY);

    /**
     * Нужен ли репортеру скриншот шага.
     *
     * <p>false, когда на телефон и так идёт живой стрим экрана: та же картинка
     * уже там, и снимать её второй раз — это лишний захват экрана (~60 мс) на
     * каждом шаге плюс двойной трафик на ту же самую картинку. Цикл агента
     * спрашивает об этом ДО снимка, а не выбрасывает готовый кадр.
     */
    default boolean needsScreenshot() {
        return true;
    }

    /** Заглушка для мест, где живой показ не нужен (demo-запуски). */
    StepReporter NONE = new StepReporter() {
        @Override
        public void onStep(int step, String description, byte[] jpeg, int w, int h, Integer tx, Integer ty) { }

        @Override
        public boolean needsScreenshot() {
            return false;
        }
    };
}
