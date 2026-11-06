package com.jarvis.relay.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто сейчас на связи и куда пересылать сообщения.
 *
 *  agents: id агента -> его сокет. У каждого ПК своё соединение.
 *  phones: id сокета телефона -> (сокет, id ПК, которым он управляет).
 *          targetAgentId == null — телефон по старой схеме: управляет
 *          «любым» агентом на связи (так было до экрана «Устройства»).
 *
 * Старая схема — дыра, как только к релею подключено больше одного ПК:
 * такой телефон командовал бы первым попавшимся чужим ПК и получал бы
 * результаты, кадры экрана и вопросы ВСЕХ агентов. Поэтому она выключена
 * по умолчанию (jarvis.legacy-phones.enabled=false): без конкретного ПК
 * телефону не достаётся ни одного агента и ни одного чужого сообщения.
 * Переключатель оставлен, чтобы не сломать старую установку на один ПК.
 *
 * ConcurrentHashMap — колбэки WebSocket приходят из разных потоков.
 */
@Component
public class SessionRegistry {

    private record PhoneEntry(WebSocketSession session, String targetAgentId) {}

    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();
    private final Map<String, PhoneEntry> phones = new ConcurrentHashMap<>();

    /** Пускать ли телефоны старой схемы (без конкретного ПК) к «любому» агенту. */
    private final boolean legacyPhones;

    public SessionRegistry(@Value("${jarvis.legacy-phones.enabled:false}") boolean legacyPhones) {
        this.legacyPhones = legacyPhones;
    }

    /** id агентов, которые сейчас стримят экран (start_screen_stream принят, stop ещё не было). */
    private final Set<String> streamingAgents = ConcurrentHashMap.newKeySet();

    public void registerAgent(String agentId, WebSocketSession session) {
        agents.put(agentId, session);
    }

    public void registerPhone(WebSocketSession session, String targetAgentId) {
        phones.put(session.getId(), new PhoneEntry(session, targetAgentId));
    }

    /** Сокет закрылся — убираем его отовсюду. Сравниваем по id сокета, а не по
     *  agentId: если агент уже переподключился, новый сокет не трогаем. */
    public void remove(WebSocketSession session) {
        agents.entrySet().removeIf(e -> {
            boolean match = e.getValue().getId().equals(session.getId());
            if (match) streamingAgents.remove(e.getKey());
            return match;
        });
        phones.remove(session.getId());
    }

    public void setStreaming(String agentId, boolean streaming) {
        if (streaming) streamingAgents.add(agentId);
        else streamingAgents.remove(agentId);
    }

    public boolean isStreaming(String agentId) {
        return streamingAgents.contains(agentId);
    }

    /**
     * Агент, которому телефон шлёт команды. null в targetAgentId — любой живой,
     * но только если старая схема явно разрешена; иначе — никто.
     */
    public WebSocketSession agentFor(String targetAgentId) {
        if (targetAgentId != null) {
            WebSocketSession s = agents.get(targetAgentId);
            return s != null && s.isOpen() ? s : null;
        }
        if (!legacyPhones) return null;
        return agents.values().stream().filter(WebSocketSession::isOpen).findFirst().orElse(null);
    }

    public boolean isAgentOnline(String targetAgentId) {
        return agentFor(targetAgentId) != null;
    }

    /** Телефоны, которым нужно переслать результат от этого агента. */
    public List<WebSocketSession> phonesOf(String agentId) {
        return phones.values().stream()
                .filter(p -> Objects.equals(p.targetAgentId(), agentId)
                        || (legacyPhones && p.targetAgentId() == null))
                .map(PhoneEntry::session)
                .filter(WebSocketSession::isOpen)
                .toList();
    }
}
