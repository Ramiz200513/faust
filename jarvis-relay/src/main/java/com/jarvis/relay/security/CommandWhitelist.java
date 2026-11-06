package com.jarvis.relay.security;

import com.jarvis.relay.protocol.Command;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The security core: only commands whose name() is in ALLOWED may reach the
 * agent. The relay checks every command here AFTER the LLM proposes it and
 * BEFORE forwarding. This is what stops a prompt-injected "shutdown" — this
 * class is the subject of the thesis's threat-model chapter.
 *
 * Marked @Component so Spring can inject it into the WebSocket handler.
 */
@Component
public class CommandWhitelist {

    // shutdown / reboot deliberately left OUT until you opt in.
    private static final Set<String> ALLOWED = Set.of(
            "set_volume", "lock_screen", "list_processes", "kill_process",
            "open_url", "open_app", "screenshot", "agent_task",
            "click", "type_text", "scroll",
            "start_screen_stream", "stop_screen_stream",
            "get_clipboard", "set_clipboard",
            "list_files", "send_file_to_phone", "send_file_to_pc",
            "cancel_task",
            // Ответ на вопрос ИИ-агента. Сам по себе ничего на ПК не делает:
            // агент примет его, только если прямо сейчас ждёт ответа на вопрос
            // с таким же случайным questionId, — иначе вернёт «вопрос неактуален».
            "answer_question",
            // Что показывать на экране ПК (рентген, панель плана). Агент принимает
            // только эти два варианта — настройки безопасности так не поменять.
            "set_display_option",
            // Мгновенные действия из закрытого списка (пауза, трек, громкость ±N, свернуть окна).
            "quick_action",
            // Несколько простых команд из одной фразы — каждая проверяется отдельно (см. denyReason).
            "sequence",
            // Список проектов git и их состояние — только чтение, без путей на диске.
            "dev_repos",
            // Поправка к идущей задаче («нет, не Пете, а Васе»). Текст проверяется
            // как цель задачи (см. denyReason) — поправка не лазейка мимо фильтра.
            "correct_task",
            // Задача агенту с фото с телефона — та же agent_task с вложением.
            "photo_task",
            // Видео-отчёт о законченной задаче — только чтение.
            "get_task_report"
    );

    /** Что может стоять внутри sequence: только простые мгновенные команды. */
    private static final Set<String> IN_SEQUENCE = Set.of(
            "set_volume", "quick_action", "open_url", "open_app", "screenshot", "lock_screen",
            "list_processes", "kill_process");

    /** Проверять ли цель задачи агенту; по умолчанию — да (и в тестах, где Spring не участвует). */
    @Value("${jarvis.forbidden-goals.enabled:true}")
    private boolean goalsGuard = true;

    public boolean isAllowed(Command command) {
        return denyReason(command) == null;
    }

    /**
     * Почему команда не пройдёт к агенту, или null — можно.
     *
     * <p>Две проверки: имя команды в списке и — для задачи ИИ-агенту — её цель.
     * Вторая закрывает обход первой: «выключи компьютер» как agent_task (см.
     * {@link ForbiddenGoals}). Выключается настройкой
     * {@code jarvis.forbidden-goals.enabled=false} — на случай ложного запрета
     * на защите.
     */
    public String denyReason(Command command) {
        if (!ALLOWED.contains(command.name())) return "команды нет в белом списке";
        if (command instanceof Command.QuickAction q && !Command.QuickAction.ACTIONS.contains(q.action())) {
            return "неизвестное быстрое действие " + q.action();
        }
        if (command instanceof Command.Sequence seq) {
            // Список — не способ протащить что-то мимо проверки: каждая команда
            // внутри проходит тот же белый список, а задачи ИИ-агенту и вложенные
            // списки внутрь не допускаются вовсе.
            if (seq.commands() == null || seq.commands().isEmpty()) return "пустой список команд";
            if (seq.commands().size() > Command.Sequence.MAX) return "слишком много команд в одной фразе";
            for (Command inner : seq.commands()) {
                if (inner == null || !IN_SEQUENCE.contains(inner.name())) {
                    return "в списке команд недопустима " + (inner == null ? "пустая команда" : inner.name());
                }
                String why = denyReason(inner);
                if (why != null) return why;
            }
        }
        if (goalsGuard) {
            // Всё, что становится целью ИИ-агента, проверяется одинаково: сама
            // задача, задача с фото и поправка к идущей задаче. Иначе «выключи
            // компьютер» можно было бы прислать поправкой к безобидной задаче.
            String goal = switch (command) {
                case Command.AgentTask task -> task.goal();
                case Command.PhotoTask photo -> photo.text();
                case Command.CorrectTask correction -> correction.text();
                default -> null;
            };
            if (goal != null) {
                String why = ForbiddenGoals.reason(goal);
                if (why != null) return "агенту нельзя поручать: " + why;
            }
        }
        return null;
    }
}
