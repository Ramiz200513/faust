package com.jarvis.relay.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.security.CommandWhitelist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Вопрос ИИ-агента и ответ на него идут ЧЕРЕЗ релей: релей разбирает JSON в
 * свой record и собирает заново. Если в record забыть поле, оно молча
 * пропадёт по дороге — эти тесты ловят именно такую рассинхронизацию
 * трёх копий протокола (Kotlin, релей, агент).
 */
class AgentQuestionProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agentQuestion_survivesTheRelayUnchanged() throws Exception {
        // Так вопрос выглядит, когда его шлёт агент.
        String fromAgent = """
                {"type":"agent_question","requestId":"t1","questionId":"q1",
                 "question":"Отправить report.pdf Лере?","options":["Да","Нет"],"timeoutSeconds":120}
                """;

        JarvisMessage msg = mapper.readValue(fromAgent, JarvisMessage.class);
        JarvisMessage.AgentQuestion q = assertInstanceOf(JarvisMessage.AgentQuestion.class, msg);
        assertEquals(List.of("Да", "Нет"), q.options());

        // Так его получит телефон — после пересборки на релее.
        String toPhone = mapper.writeValueAsString(msg);
        assertEquals(mapper.readTree(fromAgent), mapper.readTree(toPhone));
    }

    @Test
    void answerQuestion_parsesAndPassesWhitelist() throws Exception {
        Command cmd = mapper.readValue("""
                {"type":"answer_question","questionId":"q1","answer":"Да"}
                """, Command.class);

        Command.AnswerQuestion a = assertInstanceOf(Command.AnswerQuestion.class, cmd);
        assertEquals("q1", a.questionId());
        assertTrue(new CommandWhitelist().isAllowed(a), "ответ на вопрос должен проходить белый список");
    }

    @Test
    void agentPlan_survivesTheRelayUnchanged() throws Exception {
        String fromAgent = """
                {"type":"agent_plan","requestId":"t1","revision":2,"steps":[
                  {"title":"Скачать файл","status":"done","note":"report.pdf"},
                  {"title":"Открыть WhatsApp","status":"running","note":null}]}
                """;

        JarvisMessage msg = mapper.readValue(fromAgent, JarvisMessage.class);
        JarvisMessage.AgentPlan p = assertInstanceOf(JarvisMessage.AgentPlan.class, msg);
        assertEquals(JarvisMessage.PlanItemStatus.RUNNING, p.steps().get(1).status());

        assertEquals(mapper.readTree(fromAgent), mapper.readTree(mapper.writeValueAsString(msg)));
    }

    @Test
    void shutdown_stillBlocked() throws Exception {
        // Новая строка в белом списке не должна была случайно открыть лишнее.
        Command cmd = mapper.readValue("""
                {"type":"shutdown","delaySeconds":0}
                """, Command.class);
        assertFalse(new CommandWhitelist().isAllowed(cmd));
    }
}
