package com.jarvis.relay.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Несколько ПК на одном релее: телефон должен командовать только своим ПК и
 * получать сообщения только от него. Именно это делает «Несколько ПК»
 * безопасным — иначе, привязав домашний ПК, можно было бы читать экран
 * чужого рабочего.
 */
class SessionIsolationTest {

    private static WebSocketSession socket(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void phoneBoundToPc_seesOnlyItsOwnPc() {
        SessionRegistry reg = new SessionRegistry(false);
        WebSocketSession home = socket("home"), work = socket("work");
        WebSocketSession phoneHome = socket("phone-home"), phoneWork = socket("phone-work");
        reg.registerAgent("pc-home", home);
        reg.registerAgent("pc-work", work);
        reg.registerPhone(phoneHome, "pc-home");
        reg.registerPhone(phoneWork, "pc-work");

        assertSame(home, reg.agentFor("pc-home"));
        assertEquals(List.of(phoneHome), reg.phonesOf("pc-home"), "кадры домашнего ПК — только его телефону");
        assertEquals(List.of(phoneWork), reg.phonesOf("pc-work"));
    }

    @Test
    void legacyPhone_withoutPc_getsNothing_byDefault() {
        SessionRegistry reg = new SessionRegistry(false);
        reg.registerAgent("pc-home", socket("home"));
        WebSocketSession legacy = socket("legacy");
        reg.registerPhone(legacy, null);

        assertNull(reg.agentFor(null), "без ПК телефону не достаётся «первый попавшийся» агент");
        assertFalse(reg.phonesOf("pc-home").contains(legacy), "и чужие сообщения тоже не приходят");
    }

    @Test
    void legacyPhone_stillWorks_whenExplicitlyAllowed() {
        SessionRegistry reg = new SessionRegistry(true);
        WebSocketSession home = socket("home");
        reg.registerAgent("pc-home", home);
        WebSocketSession legacy = socket("legacy");
        reg.registerPhone(legacy, null);

        assertSame(home, reg.agentFor(null));
        assertTrue(reg.phonesOf("pc-home").contains(legacy));
    }
}
