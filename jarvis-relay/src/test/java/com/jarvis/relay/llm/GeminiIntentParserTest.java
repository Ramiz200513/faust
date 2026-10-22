package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the two contracts the LLM parser relies on:
 *  1. a JSON string in our wire format deserializes into the right Command;
 *  2. with no API key GeminiIntentParser transparently uses the regex stub.
 */
class GeminiIntentParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- 1. JSON (what the model returns) -> Command (Jackson, protocol format) ---

    @Test
    void parsesSetVolumeJson() throws Exception {
        Command cmd = mapper.readValue("""
                {"type":"set_volume","level":30}
                """, Command.class);

        Command.SetVolume v = assertInstanceOf(Command.SetVolume.class, cmd);
        assertEquals(30, v.level());
        assertEquals("set_volume", v.name());
    }

    @Test
    void parsesAgentTaskJson() throws Exception {
        Command cmd = mapper.readValue("""
                {"type":"agent_task","goal":"найди на ютубе видео про котов","scope":"browser","maxSteps":15}
                """, Command.class);

        Command.AgentTask t = assertInstanceOf(Command.AgentTask.class, cmd);
        assertEquals("найди на ютубе видео про котов", t.goal());
        assertEquals(AgentScope.BROWSER, t.scope());
        assertEquals(15, t.maxSteps());
    }

    // --- 2. No key -> fall back to the regex stub, no network call ---

    @Test
    void fallsBackToStubWhenNoApiKey() {
        StubIntentParser stub = new StubIntentParser();
        GeminiIntentParser parser =
                new GeminiIntentParser(stub, mapper, "", "gemini-2.0-flash");

        assertInstanceOf(Command.SetVolume.class, parser.parse("сделай потише"));
        assertInstanceOf(Command.AgentTask.class, parser.parse("найди на ютубе видео про котов"));
        assertNull(parser.parse("абракадабра"), "nonsense -> stub returns null");
    }

    // --- 3. Навыки и скрипты: без модели, одинаково с ключом и без ---

    @Test
    void skillAndScriptPhrases_goToTheAgent_asIs_evenWithAKey() {
        // Ключ «есть», но до сети дело не дойдёт: такие фразы разбираются кодом.
        GeminiIntentParser parser =
                new GeminiIntentParser(new StubIntentParser(), mapper, "fake-key-no-network", "gemini-2.0-flash");

        Command.AgentTask script = assertInstanceOf(Command.AgentTask.class,
                parser.parse("Запусти скрипт сервер диплома"));
        assertEquals("Запусти скрипт сервер диплома", script.goal(), "название скрипта — целиком, как сказано");
        assertEquals(AgentScope.SCREEN, script.scope());
        assertEquals(20, script.maxSteps());

        assertInstanceOf(Command.AgentTask.class, parser.parse("навык утро"));
        assertInstanceOf(Command.AgentTask.class, parser.parse("выполни навык утро"));

        // А «запусти блокнот» — по-прежнему просто открыть программу (без ключа — регуляркой).
        assertInstanceOf(Command.OpenApp.class, new StubIntentParser().parse("запусти блокнот"));
        assertNull(StubIntentParser.skillOrScript("навык"), "без названия — не навык");
    }
}
