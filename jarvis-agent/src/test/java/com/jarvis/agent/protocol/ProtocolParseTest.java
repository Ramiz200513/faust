package com.jarvis.agent.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Команды с телефона в том виде, как их шлёт релей, — агент обязан их прочитать. */
class ProtocolParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commandRequests_parse() throws Exception {
        for (String cmd : new String[] {
                "{\"type\":\"list_processes\"}",
                "{\"type\":\"set_volume\",\"level\":30}",
                "{\"type\":\"quick_action\",\"action\":\"next_track\",\"amount\":0}",
                "{\"type\":\"dev_repos\"}",
                "{\"type\":\"sequence\",\"commands\":[{\"type\":\"set_volume\",\"level\":30},{\"type\":\"open_url\",\"url\":\"https://youtube.com\"}]}",
                "{\"type\":\"agent_task\",\"goal\":\"g\",\"scope\":\"screen\",\"maxSteps\":20}"}) {
            String json = "{\"type\":\"command_request\",\"requestId\":\"r1\",\"command\":" + cmd + "}";
            JarvisMessage m = mapper.readValue(json, JarvisMessage.class);
            assertInstanceOf(JarvisMessage.CommandRequest.class, m, json);
        }
    }
}
