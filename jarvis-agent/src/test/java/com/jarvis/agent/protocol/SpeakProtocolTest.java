package com.jarvis.agent.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Голос «Фауста» на телефоне (23.11): агент читает speak и отвечает voice_clip — тем же
 * JSON, что ждут релей (SpeakProtocolTest там) и телефон (VoiceClip в Kotlin).
 */
class SpeakProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void speak_in_voiceClip_out() throws Exception {
        JarvisMessage.CommandRequest req = assertInstanceOf(JarvisMessage.CommandRequest.class, mapper.readValue("""
                {"type":"command_request","requestId":"v1","command":{"type":"speak","text":"Нажмите «Пуск»."}}
                """, JarvisMessage.class));
        assertEquals("Нажмите «Пуск».", assertInstanceOf(Command.Speak.class, req.command()).text());

        String out = mapper.writeValueAsString(new JarvisMessage.CommandResult("v1", CommandStatus.OK, null,
                new ResultData.VoiceClip("wav", 24000, "UklGRg==")));
        assertTrue(out.contains("\"type\":\"voice_clip\""), out);
        assertTrue(out.contains("\"sampleRate\":24000") && out.contains("\"format\":\"wav\""), out);
    }
}
