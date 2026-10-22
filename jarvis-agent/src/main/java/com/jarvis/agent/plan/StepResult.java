package com.jarvis.agent.plan;

/**
 * Чем кончилась подзадача — для следующих подзадач и для перепланирования.
 *
 * @param summary короткий итог: что написала модель в done («скачан report.pdf»)
 *                или почему остановилась. Именно так информация переходит от
 *                шага к шагу: браузерная подзадача сообщает имя скачанного
 *                файла, и подзадача в WhatsApp получает его в своём контексте.
 */
public record StepResult(PlanStep step, boolean completed, String summary) {}
