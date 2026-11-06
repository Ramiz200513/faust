package com.jarvis.relay.llm;

import com.jarvis.relay.pairing.AgentAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Агент сообщает фразы своих навыков: PUT /api/agent/skills.
 * Вход — тем же id и токеном агента, что и WebSocket и прокси к Gemini.
 * Тело: {"skills":[{"name":"отправить файл","patterns":["отпра\\S*\\s+\\S+…"]}]}.
 */
@RestController
@RequestMapping("/api/agent/skills")
public class AgentSkillsController {

    public record SkillsBody(List<SkillPhrases.SkillIn> skills) {}

    private final AgentAuthService agentAuth;
    private final SkillPhrases phrases;

    public AgentSkillsController(AgentAuthService agentAuth, SkillPhrases phrases) {
        this.agentAuth = agentAuth;
        this.phrases = phrases;
    }

    @PutMapping
    public ResponseEntity<?> replace(@RequestHeader("X-Device-Id") String deviceId,
                                     @RequestHeader("X-Device-Token") String token,
                                     @RequestBody SkillsBody body) {
        if (agentAuth.authenticateAgent(deviceId, token).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad device id or token"));
        }
        String problem = phrases.replace(deviceId, body == null ? List.of() : body.skills());
        if (problem != null) return ResponseEntity.badRequest().body(Map.of("error", problem));
        return ResponseEntity.ok(Map.of("skills", body == null || body.skills() == null ? 0 : body.skills().size()));
    }
}
