package com.jarvis.relay.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Режим наставника добавил в AgentStep два необязательных поля: replies
 * (кнопки-чипы для телефона) и silent (телефон молчит, говорит ПК).
 *
 * Релей разбирает шаг в свой record и собирает JSON заново — если поля нет
 * в record, оно молча пропадёт и телефон не покажет кнопок. Эти тесты ловят
 * именно такую рассинхронизацию трёх копий протокола (Kotlin, релей, агент),
 * а заодно проверяют, что старые шаги без новых полей читаются как раньше.
 */
class AgentStepMentorProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mentorStep_survivesTheRelayUnchanged() throws Exception {
        // Так шаг наставника выглядит, когда его шлёт агент (Jackson пишет null явно).
        String fromAgent = """
                {"type":"agent_step","requestId":"t1","step":2,
                 "description":"Нажмите «Пуск» — я показал стрелкой",
                 "screenshotBase64":null,"screenshotFormat":null,
                 "screenshotWidth":null,"screenshotHeight":null,
                 "targetX":20,"targetY":1060,
                 "replies":["Повтори","Сделай сам","Стоп"],"silent":true}
                """;

        JarvisMessage msg = mapper.readValue(fromAgent, JarvisMessage.class);
        JarvisMessage.AgentStep s = assertInstanceOf(JarvisMessage.AgentStep.class, msg);
        assertEquals(List.of("Повтори", "Сделай сам", "Стоп"), s.replies());
        assertEquals(Boolean.TRUE, s.silent());

        // Так его получит телефон — после пересборки на релее: ни одно поле не потерялось.
        String toPhone = mapper.writeValueAsString(msg);
        assertEquals(mapper.readTree(fromAgent), mapper.readTree(toPhone));
    }

    @Test
    void oldStep_withoutMentorFields_readsAsNull() throws Exception {
        // Старый агент про replies/silent не знает — шаг должен пройти как раньше.
        JarvisMessage msg = mapper.readValue("""
                {"type":"agent_step","requestId":"t1","step":1,"description":"click(512, 300)"}
                """, JarvisMessage.class);

        JarvisMessage.AgentStep s = assertInstanceOf(JarvisMessage.AgentStep.class, msg);
        assertNull(s.replies());
        assertNull(s.silent());
        assertEquals("click(512, 300)", s.description());
    }

    @Test
    void oldConstructor_stillWorks_andLeavesMentorFieldsNull() {
        // Прежние вызовы с девятью аргументами не должны сломаться при компиляции.
        var s = new JarvisMessage.AgentStep("t1", 3, "done", null, null, null, null, null, null);
        assertNull(s.replies());
        assertNull(s.silent());
    }
}
