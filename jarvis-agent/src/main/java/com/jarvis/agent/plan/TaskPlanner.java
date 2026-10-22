package com.jarvis.agent.plan;

import java.util.List;

/**
 * «Мозг» планирования: просьба пользователя -> план из подзадач.
 *
 * <p>Интерфейс — по той же причине, что {@link com.jarvis.agent.vision.VisionAgent}:
 * модель можно заменить (Gemini, локальная через Ollama), не трогая
 * {@link PlanRunner}. А в тестах подставить поддельный планировщик и проверить
 * логику выполнения без сети.
 */
public interface TaskPlanner {

    /**
     * Составить план (done пуст, failed == null) или перестроить его после
     * неудачной подзадачи.
     *
     * @param goal      просьба пользователя вместе с его ответами на вопросы
     * @param done      что уже сделано — повторять не надо
     * @param failed    подзадача, которая не удалась, или null при первом планировании
     * @param remaining что оставалось по прежнему плану
     * @return ответ планировщика; null — планировщик недоступен (сеть, непонятный
     *         ответ модели). Тогда задача выполняется прежним способом, без плана.
     */
    PlannerReply plan(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining);
}
