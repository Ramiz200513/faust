package com.jarvis.relay.llm;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Релей узнаёт фразу навыка без модели — по выражению, которое прислал агент.
 * Выражение ниже — ровно то, что SkillTemplate.toRelayRegex строит для
 * шаблона «отправь {1} {2}» (показ «отправь Пете отчёт»).
 */
class SkillPhrasesTest {

    private static final String SEND = "\\Qотпра\\E\\S*\\s+\\S+(?:\\s+\\S+){0,3}\\s+\\S+(?:\\s+\\S+){0,3}";

    @Test
    void phraseOfThisPc_isRecognized_withNormalization() {
        SkillPhrases p = new SkillPhrases();
        assertNull(p.replace("pc-home", List.of(
                new SkillPhrases.SkillIn("отправить файл", List.of(SEND)),
                new SkillPhrases.SkillIn("утро", List.of("\\Qдоброе утро\\E")))));

        assertEquals("отправить файл", p.match("pc-home", "Фауст, отправь Маше расписание!"));
        assertEquals("утро", p.match("pc-home", "Доброе утро."));
        assertNull(p.match("pc-home", "открой ютуб"));
    }

    @Test
    void skillsOfOnePc_doNotLeakToAnother() {
        SkillPhrases p = new SkillPhrases();
        p.replace("pc-home", List.of(new SkillPhrases.SkillIn("отправить файл", List.of(SEND))));
        assertNull(p.match("pc-work", "отправь Маше расписание"), "навыки домашнего ПК — не рабочего");
        assertNull(p.match(null, "отправь Маше расписание"));
    }

    @Test
    void badInput_isRejected_andOldPhrasesStay() {
        SkillPhrases p = new SkillPhrases();
        p.replace("pc-home", List.of(new SkillPhrases.SkillIn("утро", List.of("\\Qдоброе утро\\E"))));

        assertNotNull(p.replace("pc-home", List.of(new SkillPhrases.SkillIn("x", List.of("(")))), "битое выражение");
        assertNotNull(p.replace("pc-home", List.of(new SkillPhrases.SkillIn("x", List.of("a".repeat(500))))));
        assertNotNull(p.replace("pc-home", Collections.nCopies(51, new SkillPhrases.SkillIn("x", List.of("a")))));
        assertEquals("утро", p.match("pc-home", "доброе утро"), "после отказа прежние фразы на месте");
        assertNull(p.match("pc-home", "доброе утро " + "очень ".repeat(60)), "слишком длинная фраза");
    }
}
