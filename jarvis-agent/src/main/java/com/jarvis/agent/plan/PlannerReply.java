package com.jarvis.agent.plan;

import java.util.List;

/**
 * Что может ответить планировщик. Три исхода, и все три — нормальные:
 * план, вопрос человеку, честный отказ.
 */
public sealed interface PlannerReply {

    /**
     * План.
     *
     * @param confirm   вопрос-подтверждение перед необратимыми шагами («Отправить
     *                  report.pdf Лере и Маше в WhatsApp?») или пустая строка
     * @param listSteps сколько шагов код размножил по списку получателей («для каждого»):
     *                  на столько план может быть длиннее обычного лимита — список и так
     *                  ограничен (RecipientLists.MAX), а обрезать его молча нельзя
     */
    record Steps(List<PlanStep> steps, String confirm, int listSteps) implements PlannerReply {
        public Steps {
            steps = List.copyOf(steps);
            confirm = confirm == null ? "" : confirm.strip();
        }

        /** Обычный план — без шагов, размноженных по списку получателей. */
        public Steps(List<PlanStep> steps, String confirm) {
            this(steps, confirm, 0);
        }
    }

    /**
     * Без человека план не составить: не сказано, какой файл, кому, что написать.
     * Спросить ДО того, как агент хоть что-то сделает, — дешевле, чем
     * обнаружить нехватку данных на пятом шаге.
     */
    record Ask(String question, List<String> options) implements PlannerReply {
        public Ask {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Задача невыполнима или опасна (пароли, платежи, массовая рассылка). */
    record GiveUp(String reason) implements PlannerReply {}
}
