package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Связка «своя модель → Gemini → регулярки» без сети: обе модели и база
 * уроков — поддельные. Проверяется то, за что отвечает код: кому верить,
 * когда переспрашивать и чему учиться.
 */
class HybridIntentParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- поддельные участники -------------------------------------------------

    /** «Наша модель»: отвечает заранее заданным текстом с заданной уверенностью. */
    private final class FakeLocal extends LocalIntentParser {
        String raw;          // null — модель недоступна
        double confidence;
        int calls;

        FakeLocal(String raw, double confidence) {
            super(mapper, "http://fake", "faust-intent");
            this.raw = raw;
            this.confidence = confidence;
        }

        @Override
        public Result parse(String phrase) {
            calls++;
            return raw == null ? Result.unavailable() : interpret(phrase, raw, confidence, 5);
        }
    }

    /** «Gemini»: отвечает заданной командой, null («не понял») или падает (нет интернета). */
    private final class FakeGemini extends GeminiIntentParser {
        Command answer;
        boolean down;
        int calls;

        FakeGemini(Command answer) {
            super(new StubIntentParser(), mapper, "fake-key", "gemini-test");
            this.answer = answer;
        }

        @Override
        public Command parseModelOnly(String phrase) {
            calls++;
            if (down) throw new IllegalStateException("нет интернета");
            return answer;
        }
    }

    /** Уроки в памяти вместо базы. */
    private static final class FakeLessons extends IntentLessons {
        final Map<String, Command> confirmed = new HashMap<>();
        final List<String> proposed = new ArrayList<>();

        FakeLessons() {
            super(null, null);
        }

        @Override
        public Optional<Command> recall(String phrase) {
            return Optional.ofNullable(confirmed.get(phrase));
        }

        @Override
        public void propose(String requestId, String phrase, Command command, String localAnswer) {
            if (requestId != null) proposed.add(phrase + " -> " + command.name() + " (своя модель: " + localAnswer + ")");
        }
    }

    private final FakeLessons lessons = new FakeLessons();

    private HybridIntentParser hybrid(FakeLocal local, FakeGemini gemini, String engine) {
        return new HybridIntentParser(local, gemini, new StubIntentParser(), lessons, engine, 0.5, 2.0);
    }

    // --- своя модель справилась --------------------------------------------------

    @Test
    void confidentLocalAnswer_isUsed_andGeminiIsNotAsked() {
        FakeLocal local = new FakeLocal("{\"type\":\"set_volume\",\"level\":30}", 0.97);
        FakeGemini gemini = new FakeGemini(new Command.SetVolume(80));

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("сделай потише", "req-1");

        assertEquals(new Command.SetVolume(30), d.command());
        assertEquals("local", d.source());
        assertEquals(0, gemini.calls, "уверенный ответ своей модели Gemini не перепроверяет");
        assertTrue(lessons.proposed.isEmpty());
    }

    @Test
    void agentTask_getsThePhraseAsGoal_modelOnlyChoosesScope() {
        FakeLocal local = new FakeLocal("{\"type\":\"agent_task\",\"scope\":\"screen\"}", 0.9);

        Command cmd = hybrid(local, new FakeGemini(null), "auto")
                .decide("скачай отчёт и отправь Лере в телеграм", "req-1").command();

        Command.AgentTask task = assertInstanceOf(Command.AgentTask.class, cmd);
        assertEquals("скачай отчёт и отправь Лере в телеграм", task.goal(), "цель — фраза целиком, модель её не переписывает");
        assertEquals(AgentScope.SCREEN, task.scope());
        assertEquals(20, task.maxSteps());
    }

    @Test
    void gitRequests_goToAgent_withoutAnyModel() {
        FakeLocal local = new FakeLocal("{\"type\":\"open_app\",\"appName\":\"git\"}", 0.99);
        FakeGemini gemini = new FakeGemini(null);
        HybridIntentParser h = hybrid(local, gemini, "auto");

        for (String phrase : List.of("git status демо", "Фауст, запушь демо", "закоммить и запушь", "гит пул диплом")) {
            HybridIntentParser.Decision d = h.decide(phrase, null);
            Command.AgentTask task = assertInstanceOf(Command.AgentTask.class, d.command(), phrase);
            assertEquals(phrase, task.goal());
            assertEquals("shortcut", d.source(), phrase);
        }
        assertEquals(0, local.calls + gemini.calls, "git-фразы модели не разбирают");
        assertNull(StubIntentParser.skillOrScript("загугли что такое git"), "слово git в середине — не команда");
    }

    // --- своя модель не справилась -> Gemini + урок ----------------------------------

    @Test
    void unsureLocalAnswer_goesToGemini_andBecomesALesson() {
        FakeLocal local = new FakeLocal("{\"type\":\"open_app\",\"appName\":\"kinopoisk\"}", 0.31);
        FakeGemini gemini = new FakeGemini(new Command.OpenUrl("https://kinopoisk.ru"));

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("включи кинопоиск", "req-7");

        assertEquals(new Command.OpenUrl("https://kinopoisk.ru"), d.command());
        assertEquals("gemini", d.source());
        assertEquals(1, lessons.proposed.size(), "чего не знала своя модель — тому её научим");
        assertTrue(lessons.proposed.get(0).contains("kinopoisk"), lessons.proposed.get(0));
    }

    @Test
    void garbageFromLocalModel_neverReachesTheAgent() {
        for (String garbage : List.of("конечно! вот команда", "{\"type\":\"shutdown\"}",
                "{\"type\":\"set_volume\",\"level\":900}", "{\"type\":\"open_url\",\"url\":\"не адрес\"}",
                "{\"type\":\"agent_task\",\"scope\":\"всё\"}")) {
            FakeLocal local = new FakeLocal(garbage, 0.99);
            FakeGemini gemini = new FakeGemini(new Command.Screenshot());

            HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("что-нибудь", "req-1");

            assertEquals("gemini", d.source(), "мусор «" + garbage + "» должен уйти на перепроверку");
            assertEquals(new Command.Screenshot(), d.command());
        }
    }

    @Test
    void localUnknown_isDoubleCheckedByGemini() {
        FakeLocal local = new FakeLocal("{\"type\":\"unknown\"}", 0.9);
        FakeGemini gemini = new FakeGemini(null); // и Gemini не понял

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("как дела", "req-1");

        assertNull(d.command());
        assertEquals(1, gemini.calls);
        assertTrue(lessons.proposed.isEmpty(), "«не понял» — не урок");
    }

    // --- память уроков -------------------------------------------------------------

    @Test
    void confirmedLesson_answersWithoutAnyModel() {
        lessons.confirmed.put("включи кинопоиск", new Command.OpenUrl("https://kinopoisk.ru"));
        FakeLocal local = new FakeLocal("{\"type\":\"unknown\"}", 0.9);
        FakeGemini gemini = new FakeGemini(null);

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("включи кинопоиск", "req-1");

        assertEquals("memory", d.source());
        assertEquals(new Command.OpenUrl("https://kinopoisk.ru"), d.command());
        assertEquals(0, local.calls + gemini.calls);
    }

    // --- интернета нет / своей модели нет ---------------------------------------------

    @Test
    void withoutInternet_validLocalAnswerIsBetterThanNothing() {
        FakeLocal local = new FakeLocal("{\"type\":\"lock_screen\"}", 0.35); // ниже порога
        FakeGemini gemini = new FakeGemini(null);
        gemini.down = true;

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("запри ноут", "req-1");

        assertEquals(new Command.LockScreen(), d.command());
        assertEquals("local-offline", d.source());
    }

    @Test
    void afterGeminiFailure_itIsNotAskedAgainForAWhile() {
        // Кончилась квота (так и было 05.10): каждая непонятная фраза не должна
        // заново ждать заведомо неудачного запроса.
        FakeLocal local = new FakeLocal("{\"type\":\"unknown\"}", 0.9);
        FakeGemini gemini = new FakeGemini(null);
        gemini.down = true;
        HybridIntentParser h = hybrid(local, gemini, "auto");

        h.decide("как дела", "r1");
        h.decide("что нового", "r2");
        h.decide("расскажи анекдот", "r3");

        assertEquals(1, gemini.calls, "после отказа Gemini минуту не трогаем");

        // А с нулевой паузой (так было бы через минуту) — спрашиваем снова.
        HybridIntentParser eager = hybrid(local, gemini, "auto");
        eager.geminiRetryMs = 0;
        eager.decide("как дела", "r4");
        eager.decide("что нового", "r5");
        assertEquals(3, gemini.calls);
    }

    @Test
    void withoutLocalModel_worksAsBefore_andTeachesNothing() {
        FakeLocal local = new FakeLocal(null, 0); // модель ещё не установлена
        FakeGemini gemini = new FakeGemini(new Command.SetVolume(30));

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("сделай потише", "req-1");

        assertEquals("gemini", d.source());
        assertTrue(lessons.proposed.isEmpty(), "модель не спрашивали — значит, это не её ошибка");
    }

    @Test
    void engineSwitch_local_neverCallsGemini_andGemini_neverCallsLocal() {
        FakeLocal local = new FakeLocal("{\"type\":\"unknown\"}", 0.9);
        FakeGemini gemini = new FakeGemini(new Command.Screenshot());

        assertNull(hybrid(local, gemini, "local").decide("как дела", "r").command());
        assertEquals(0, gemini.calls);

        FakeLocal local2 = new FakeLocal("{\"type\":\"lock_screen\"}", 0.99);
        assertEquals(new Command.Screenshot(), hybrid(local2, gemini, "gemini").decide("покажи экран", "r").command());
        assertEquals(0, local2.calls);
    }

    @Test
    void skillsAndScripts_stillBypassAllModels() {
        FakeLocal local = new FakeLocal("{\"type\":\"unknown\"}", 0.9);
        FakeGemini gemini = new FakeGemini(null);

        HybridIntentParser.Decision d = hybrid(local, gemini, "auto").decide("запусти скрипт сервер диплома", "r");

        assertEquals("shortcut", d.source());
        assertInstanceOf(Command.AgentTask.class, d.command());
        assertEquals(0, local.calls + gemini.calls);
    }

    // --- мелочи разбора ответа ---------------------------------------------------------

    @Test
    void url_withoutScheme_isCompleted_andConfidenceIsTheProductOfTokenProbabilities() throws Exception {
        assertEquals("https://youtube.com", LocalIntentParser.normalizeUrl("youtube.com"));
        assertNull(LocalIntentParser.normalizeUrl("ютуб"));

        // два токена с вероятностями 0.5 и 0.5 -> ответ целиком 0.25
        double c = LocalIntentParser.confidence(mapper.readTree(
                "[{\"token\":\"a\",\"logprob\":-0.6931472},{\"token\":\"b\",\"logprob\":-0.6931472}]"));
        assertEquals(0.25, c, 1e-6);
    }
}
