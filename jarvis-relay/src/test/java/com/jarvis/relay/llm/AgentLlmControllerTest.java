package com.jarvis.relay.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.pairing.AgentAuthService;
import com.jarvis.relay.persistence.AgentLlmLogRepository;
import com.jarvis.relay.persistence.Device;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Проверяет границы прокси без реального похода в Gemini и без БД:
 * отказ без валидного deviceId/token, и отказ без ключа на релее.
 */
class AgentLlmControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentAuthService agentAuth = mock(AgentAuthService.class);
    private final AgentLlmLogRepository llmLog = mock(AgentLlmLogRepository.class);

    private static byte[] body(String prompt) {
        return ("{\"prompt\":\"" + prompt + "\"}").getBytes();
    }

    @Test
    void rejectsWithoutValidAgentToken() {
        when(agentAuth.authenticateAgent("agent-1", "wrong-token")).thenReturn(Optional.empty());
        AgentLlmController controller = new AgentLlmController(agentAuth, llmLog, mapper, "some-key", "gemini-2.5-flash", 0);

        ResponseEntity<?> resp = controller.generate("agent-1", "wrong-token", body("привет"));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void rejectsWhenApiKeyMissing() {
        when(agentAuth.authenticateAgent("agent-1", "good-token"))
                .thenReturn(Optional.of(mock(Device.class)));
        AgentLlmController controller = new AgentLlmController(agentAuth, llmLog, mapper, "", "gemini-2.5-flash", 0);

        ResponseEntity<?> resp = controller.generate("agent-1", "good-token", body("привет"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }
}
