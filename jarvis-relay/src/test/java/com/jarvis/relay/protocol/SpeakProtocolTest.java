package com.jarvis.relay.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.relay.security.CommandWhitelist;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Голос «Фауста» на телефоне (23.11): телефон шлёт speak, агент отвечает voice_clip.
 * Релей разбирает оба сообщения в свои record и собирает JSON заново — если типа нет
 * в JsonSubTypes, сообщение не пройдёт вовсе. Плюс белый список: speak разрешён, но
 * только с короткой непустой фразой.
 */
class SpeakProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void speakCommand_fromPhone_isParsed() throws Exception {
        String fromPhone = """
                {"type":"command_request","requestId":"v1",
                 "command":{"type":"speak","text":"Готово! Вы сделали это сами."}}
                """;
        JarvisMessage.CommandRequest req = assertInstanceOf(JarvisMessage.CommandRequest.class,
                mapper.readValue(fromPhone, JarvisMessage.class));
        Command.Speak sp = assertInstanceOf(Command.Speak.class, req.command());
        assertEquals("Готово! Вы сделали это сами.", sp.text());
        assertEquals("speak", sp.name());
        assertTrue(mapper.writeValueAsString(req).contains("\"type\":\"speak\""), "агенту уходит тот же тип");
    }

    @Test
    void voiceClip_fromAgent_reachesPhoneUnchanged() throws Exception {
        String fromAgent = """
                {"type":"command_result","requestId":"v1","status":"ok","message":null,
                 "data":{"type":"voice_clip","format":"wav","sampleRate":24000,"base64":"UklGRg=="}}
                """;
        JarvisMessage.CommandResult r = assertInstanceOf(JarvisMessage.CommandResult.class,
                mapper.readValue(fromAgent, JarvisMessage.class));
        ResultData.VoiceClip clip = assertInstanceOf(ResultData.VoiceClip.class, r.data());
        assertEquals(24000, clip.sampleRate());
        String toPhone = mapper.writeValueAsString(r);
        assertTrue(toPhone.contains("\"type\":\"voice_clip\"") && toPhone.contains("UklGRg=="), toPhone);
    }

    @Test
    void whitelist_allowsShortPhrasesOnly() {
        CommandWhitelist w = new CommandWhitelist();
        assertNull(w.denyReason(new Command.Speak("Нажмите «Пуск».")));
        assertNotNull(w.denyReason(new Command.Speak("")), "пустая фраза — незачем гонять ПК");
        assertNotNull(w.denyReason(new Command.Speak("а".repeat(301))), "длинный текст — не реплика");
    }
}
