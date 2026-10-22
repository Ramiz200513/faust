package com.jarvis.agent.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.agent.protocol.Command;
import com.jarvis.agent.protocol.JarvisMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Разбор ответов модели с новым действием ask и JSON протокола — ровно те
 * строки, которые реально летят по проводу.
 */
class GeminiVisionAgentParseTest {

    // relayLlm не нужен: разбор ответа к сети не обращается.
    private final GeminiVisionAgent brain = new GeminiVisionAgent(null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ask_inWindowMode_questionAndOptions() throws Exception {
        AgentAction a = brain.parseAction(
                "{\"action\":\"ask\",\"question\":\"Какой из двух чатов «Лера»?\",\"options\":[\"Лера Иванова\",\"Лера (работа)\"]}");

        assertTrue(a.isAsk());
        assertEquals("Какой из двух чатов «Лера»?", a.text());
        assertEquals(List.of("Лера Иванова", "Лера (работа)"), a.options());
        assertEquals("ask(\"Какой из двух чатов «Лера»?\" [Лера Иванова / Лера (работа)])", a.toString());
    }

    @Test
    void ask_optionsAsSingleString_areSplit() throws Exception {
        ScreenAction a = brain.parseScreenAction(
                "```json\n{\"action\":\"ask\",\"question\":\"Отправить?\",\"options\":\"Да / Нет\"}\n```");

        assertTrue(a.isAsk());
        assertEquals(List.of("Да", "Нет"), a.options());
    }

    @Test
    void fileActions_pathAndName() throws Exception {
        AgentAction paste = brain.parseAction(
                "{\"action\":\"paste_file\",\"path\":\"C:\\\\Users\\\\user\\\\Downloads\\\\report.pdf\"}");
        assertEquals("C:\\Users\\user\\Downloads\\report.pdf", paste.text());
        assertEquals("paste_file(\"C:\\Users\\user\\Downloads\\report.pdf\")", paste.toString());

        ScreenAction find = brain.parseScreenAction("{\"action\":\"find_file\",\"name\":\"отчёт\"}");
        assertEquals("find_file(\"отчёт\")", find.toString());
    }

    @Test
    void oldActions_stillParsedAsBefore() throws Exception {
        assertEquals("open_app(\"Telegram\")",
                brain.parseAction("{\"action\":\"open_app\",\"name\":\"Telegram\"}").toString());
        assertEquals("key(ctrl+f)",
                brain.parseScreenAction("{\"action\":\"key\",\"keys\":\"ctrl+f\"}").toString());
        assertNull(brain.parseAction("{\"action\":\"click\",\"index\":3}").options());
    }

    @Test
    void wire_answerQuestion_fromPhone() throws Exception {
        // Ровно тот JSON, который собирает телефон (см. ProtocolTest в :shared).
        String json = "{\"type\":\"command_request\",\"requestId\":\"a2\","
                + "\"command\":{\"type\":\"answer_question\",\"questionId\":\"q1\",\"answer\":\"Да\"}}";

        JarvisMessage.CommandRequest req = (JarvisMessage.CommandRequest) mapper.readValue(json, JarvisMessage.class);

        assertEquals(new Command.AnswerQuestion("q1", "Да"), req.command());
    }

    @Test
    void wire_agentQuestion_toPhone() throws Exception {
        String json = mapper.writeValueAsString(
                new JarvisMessage.AgentQuestion("t1", "q1", "Кому?", null, 120));

        assertTrue(json.contains("\"type\":\"agent_question\""), json);
        assertTrue(json.contains("\"options\":[]"), json);
    }
}
