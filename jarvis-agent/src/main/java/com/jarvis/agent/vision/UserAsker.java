package com.jarvis.agent.vision;

import java.util.List;

/**
 * Способ задать вопрос живому человеку посреди задачи и дождаться ответа.
 *
 * <p>Интерфейс нужен по той же причине, что и {@link StepReporter} с
 * {@link CancelSignal}: циклы {@link BrowserAgent} и {@link ScreenAgent} не
 * должны знать, КАК вопрос доходит до человека. Сейчас он уходит на телефон
 * через релей (см. CommandExecutor), но в demo-запусках спросить некого — там
 * используется {@link #NONE}, и цикл продолжает работать сам.
 */
public interface UserAsker {

    /**
     * Задать вопрос и ждать ответа. Блокирует поток задачи — это нормально:
     * пока человек думает, агенту всё равно нечего делать.
     *
     * @param question текст вопроса
     * @param options  варианты для кнопок (может быть пустым — ответ своими словами)
     * @param cancel   отмена задачи с телефона должна прерывать и ожидание ответа
     * @return ответ человека; null — ответа нет (истекло время, задачу отменили,
     *         или спросить просто некого)
     */
    String ask(String question, List<String> options, CancelSignal cancel);

    /** Можно ли вообще кого-то спросить. false — вопрос сразу вернёт null. */
    default boolean available() {
        return true;
    }

    /** Спросить некого (demo-запуски, старые перегрузки run(...)). */
    UserAsker NONE = new UserAsker() {
        @Override
        public String ask(String question, List<String> options, CancelSignal cancel) {
            return null;
        }

        @Override
        public boolean available() {
            return false;
        }
    };
}
