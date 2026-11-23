package com.jarvis.relay.llm;

import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import com.jarvis.relay.security.CommandWhitelist;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Режим наставника: «покажи, как …», «научи меня …» релей узнаёт правилом,
 * без моделей, и отдаёт агенту фразу целиком. Не менее важно обратное —
 * обычные «покажи рабочий стол», «покажи коммиты», «покажи папку документы»
 * правило перехватывать не должно, а запрещённая цель не должна пролезть
 * под видом урока.
 */
class MentorPhrasesTest {

    /** Фраза стала уроком: agent_task с ней как есть, scope=screen, 20 шагов. */
    private static void assertLesson(String phrase) {
        Command c = StubIntentParser.skillOrScript(phrase);
        Command.AgentTask task = assertInstanceOf(Command.AgentTask.class, c, phrase);
        assertEquals(phrase.strip(), task.goal(), "фраза уходит агенту без изменений: " + phrase);
        assertEquals(AgentScope.SCREEN, task.scope());
        assertEquals(20, task.maxSteps());
        assertTrue(StubIntentParser.isMentorRequest(phrase.toLowerCase().strip()), phrase);
    }

    @Test
    void lessonPhrases_becomeAgentTask_withoutModels() {
        assertLesson("Фауст, покажи, как включить экранную лупу");
        assertLesson("покажи как включить экранную лупу");
        assertLesson("покажи мне, как сделать скриншот части экрана");
        assertLesson("Фауст покажи мне как поменять обои");
        assertLesson("подскажи, как закрепить окно поверх всех");
        assertLesson("Фауст, подскажи как очистить корзину");
        assertLesson("научи меня включать экранную лупу");
        assertLesson("Фауст, научи меня, как открыть диспетчер задач");
        assertLesson("научи пользоваться буфером обмена");
        assertLesson("объясни, как поменять язык ввода");
        assertLesson("Faust, объясни мне как включить ночной режим!");
    }

    @Test
    void bareHowQuestion_isALesson() {
        // Так человек сказал на живом уроке 19.11 — и агент включил лупу сам вместо урока.
        assertLesson("как включить экранную лупу");
        assertLesson("Как включить экранную Лупу?");
        assertLesson("Фауст, как сделать скриншот");
        assertLesson("а как поменять обои");
        assertLesson("как мне найти загрузки");
        assertLesson("как можно настроиться на ночной режим");
    }

    @Test
    void howQuestionsWithoutInfinitive_areNotLessons() {
        // Нет глагола «что сделать» сразу после «как» — это разговор, его разберут модели.
        assertNull(StubIntentParser.skillOrScript("как дела"));
        assertNull(StubIntentParser.skillOrScript("как тебя зовут"));
        assertNull(StubIntentParser.skillOrScript("как погода в Алматы"));
        assertNull(StubIntentParser.skillOrScript("как быть"));
        assertNull(StubIntentParser.skillOrScript("как жить дальше"));
        // «как» в середине фразы — не вопрос-урок.
        assertNull(StubIntentParser.skillOrScript("открой блокнот как администратор"));
    }

    @Test
    void lessonWins_overQuickCommand_insideIt() {
        // «свернуть все окна» внутри — но это урок, а не команда свернуть окна прямо сейчас.
        // skillOrScript в цепочке стоит раньше QuickPhrases, поэтому урок побеждает.
        assertLesson("покажи, как свернуть все окна");
    }

    @Test
    void ordinaryShowCommands_areNotLessons() {
        // Быстрая команда — её разберёт QuickPhrases, правилу наставника тут делать нечего.
        assertNull(StubIntentParser.skillOrScript("покажи рабочий стол"));
        assertNotNull(QuickPhrases.parse("покажи рабочий стол"), "это по-прежнему быстрая команда");

        // Git — своё правило; тоже agent_task, но не урок.
        assertNotNull(StubIntentParser.skillOrScript("покажи коммиты"));
        assertFalse(StubIntentParser.isMentorRequest("покажи коммиты"));

        // Открыть папку — это open_app у моделей, а не задача агенту.
        assertNull(StubIntentParser.skillOrScript("покажи папку документы"));
        assertNull(StubIntentParser.skillOrScript("Фауст, покажи папку с документами"));
        assertNull(StubIntentParser.skillOrScript("покажи процессы"));
    }

    @Test
    void smallTalk_isNotALesson() {
        // «как дела» и «научи меня» без задачи — болтовня, пусть разбирают модели.
        assertNull(StubIntentParser.skillOrScript("покажи, как дела"));
        assertNull(StubIntentParser.skillOrScript("научи меня"));
        assertNull(StubIntentParser.skillOrScript("Фауст, научи меня чему-нибудь"));
        assertNull(StubIntentParser.skillOrScript("объясни как"));
        // «научись» — не «научи»: границу слова держит обязательный пробел/запятая.
        assertNull(StubIntentParser.skillOrScript("научись открывать почту"));
    }

    @Test
    void forbiddenGoal_isStillBlocked_whenAskedAsALesson() {
        // Правило наставника выдаёт agent_task, но белый список (ForbiddenGoals)
        // проверяет его цель так же строго, как любую другую.
        CommandWhitelist whitelist = new CommandWhitelist();
        Command shutdown = StubIntentParser.skillOrScript("покажи, как выключить компьютер");
        assertNotNull(shutdown);
        assertFalse(whitelist.isAllowed(shutdown), "урок не открывает обход запрета на выключение");

        Command pay = StubIntentParser.skillOrScript("научи меня оплати интернет");
        assertNotNull(pay);
        assertFalse(whitelist.isAllowed(pay));

        // А безобидный урок проходит.
        assertTrue(whitelist.isAllowed(StubIntentParser.skillOrScript("покажи, как включить экранную лупу")));
    }

    @Test
    void stubParser_alsoRoutesLessonsToAgent() {
        // Запасной разборщик (без сети) должен вести себя так же.
        Command c = new StubIntentParser().parse("Фауст, покажи, как открыть блокнот");
        Command.AgentTask task = assertInstanceOf(Command.AgentTask.class, c);
        assertEquals("Фауст, покажи, как открыть блокнот", task.goal());
    }
}
