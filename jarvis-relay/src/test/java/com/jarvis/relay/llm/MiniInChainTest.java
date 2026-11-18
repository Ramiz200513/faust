package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * «Фауст-мини» в цепочке разбора — на НАСТОЯЩИХ весах (resources/mini), без сети:
 * Qwen и Gemini подделаны и считают, сколько раз их спросили. Проверяется главное:
 * в чём мини уверена — решает сама и быстро; в чём нет — фраза идёт к Qwen, как раньше;
 * без мини цепочка работает по-старому.
 */
class MiniInChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static MiniIntentModel model;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream json = MiniInChainTest.class.getResourceAsStream("/mini/faust-mini.json");
             InputStream bin = MiniInChainTest.class.getResourceAsStream("/mini/faust-mini.bin")) {
            model = MiniIntentModel.load(json, bin, MAPPER);
        }
    }

    /** «Qwen», которая на всё отвечает задачей агенту и считает вызовы. */
    private static final class CountingQwen extends LocalIntentParser {
        int calls;

        CountingQwen() {
            super(MAPPER, "http://fake", "faust-intent");
        }

        @Override
        public Result parse(String phrase) {
            calls++;
            return interpret(phrase, "{\"type\":\"agent_task\",\"scope\":\"screen\"}", 0.97, 200);
        }
    }

    private static final class NoGemini extends GeminiIntentParser {
        int calls;

        NoGemini() {
            super(new StubIntentParser(), MAPPER, "fake-key", "gemini-test", 15);
        }

        @Override
        public Command parseModelOnly(String phrase) {
            calls++;
            return null;
        }
    }

    private static final class NoLessons extends IntentLessons {
        NoLessons() {
            super(null, null);
        }

        @Override
        public Optional<Command> recall(String phrase) {
            return Optional.empty();
        }

        @Override
        public void propose(String requestId, String phrase, Command command, String localAnswer) { }
    }

    private HybridIntentParser chain(CountingQwen qwen, NoGemini gemini, boolean withMini) {
        HybridIntentParser h = new HybridIntentParser(qwen, gemini, new StubIntentParser(), new NoLessons(),
                "auto", 0.9, 2.0, true);
        if (withMini) h.setMini(new MiniIntentParser(qwen, model, 0.993));
        return h;
    }

    @Test
    void sureSimpleCommand_decidedByMini_withoutQwen() {
        CountingQwen qwen = new CountingQwen();
        HybridIntentParser h = chain(qwen, new NoGemini(), true);

        HybridIntentParser.Decision d = h.decide("открой ютуб", null);

        assertEquals("mini", d.source());
        assertEquals(new Command.OpenUrl("https://youtube.com"), d.command());
        assertEquals(0, qwen.calls, "Qwen не спрашивали — мини уверена");
        assertTrue(d.confidence() >= 0.993);
    }

    @Test
    void unsureOrUnknown_goesToQwen_asBefore() {
        CountingQwen qwen = new CountingQwen();
        HybridIntentParser h = chain(qwen, new NoGemini(), true);

        HybridIntentParser.Decision d = h.decide("забронируй мне столик через сайт ресторана на вечер пятницы", null);

        assertEquals("local", d.source(), "мини не уверена — решает Qwen");
        assertEquals(1, qwen.calls);
        assertEquals(new Command.AgentTask("забронируй мне столик через сайт ресторана на вечер пятницы",
                AgentScope.SCREEN, 20), d.command());
    }

    @Test
    void compositeParts_byMini() {
        CountingQwen qwen = new CountingQwen();
        HybridIntentParser h = chain(qwen, new NoGemini(), true);

        HybridIntentParser.Decision d = h.decide("сделай погромче и открой ютуб", null);

        assertEquals("composite", d.source());
        assertInstanceOf(Command.Sequence.class, d.command());
        assertEquals(0, qwen.calls, "обе части — простые команды, мини уверена в каждой");
    }

    @Test
    void withoutMini_chainUnchanged() {
        CountingQwen qwen = new CountingQwen();
        HybridIntentParser h = chain(qwen, new NoGemini(), false);

        HybridIntentParser.Decision d = h.decide("открой ютуб", null);

        assertEquals("local", d.source());
        assertEquals(1, qwen.calls);
    }

    @Test
    void disabledOrTooLong_isUnavailable() {
        MiniIntentParser off = new MiniIntentParser(new CountingQwen(), null, 0.993);
        assertFalse(off.available());
        assertEquals(LocalIntentParser.Kind.UNAVAILABLE, off.parse("открой ютуб").kind());
        MiniIntentParser on = new MiniIntentParser(new CountingQwen(), model, 0.993);
        assertEquals(LocalIntentParser.Kind.UNAVAILABLE, on.parse("слово ".repeat(80)).kind(), "длиннее 300 символов");
    }
}
