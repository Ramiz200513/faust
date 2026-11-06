package com.jarvis.relay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import com.jarvis.relay.protocol.JarvisMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Поправка на ходу и задача с фото — новые пути, по которым текст человека
 * становится целью ИИ-агента. Проверяется, что фильтр запрещённых целей стоит
 * и на них: иначе «выключи компьютер» прошло бы поправкой к безобидной задаче.
 */
class CorrectionAndPhotoWhitelistTest {

    private final CommandWhitelist whitelist = new CommandWhitelist();

    @Test
    void ordinaryCorrectionAndPhotoTask_areAllowed() {
        assertNull(whitelist.denyReason(new Command.CorrectTask("нет, не Пете, а Васе")));
        assertNull(whitelist.denyReason(new Command.PhotoTask("сохрани в конспект по базам данных", "AAAA")));
        assertNull(whitelist.denyReason(new Command.GetTaskReport("task-1", false)));
    }

    @Test
    void forbiddenGoal_isBlocked_inCorrectionAndPhotoTask_likeInAgentTask() {
        String bad = "выключи компьютер";
        assertNotNull(whitelist.denyReason(new Command.AgentTask(bad, AgentScope.SCREEN, 15)), "исходная проверка");
        assertNotNull(whitelist.denyReason(new Command.CorrectTask(bad)), "поправка — не лазейка");
        assertNotNull(whitelist.denyReason(new Command.PhotoTask(bad, "AAAA")), "фото — не лазейка");
    }

    @Test
    void newCommands_fromPhone_areParsedByRelay() throws Exception {
        // Ровно такой JSON шлёт телефон (kotlinx.serialization, encodeDefaults).
        ObjectMapper mapper = new ObjectMapper();
        JarvisMessage m = mapper.readValue("""
                {"type":"command_request","requestId":"g1",
                 "command":{"type":"get_task_report","taskId":"task-1","asGif":true}}""", JarvisMessage.class);
        Command.GetTaskReport c = assertInstanceOf(Command.GetTaskReport.class,
                ((JarvisMessage.CommandRequest) m).command());
        assertTrue(c.asGif());
    }
}
