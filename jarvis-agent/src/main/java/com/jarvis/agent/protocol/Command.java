package com.jarvis.agent.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Java mirror of the Kotlin `Command` sealed interface. Jackson reads the
 * "type" field and builds the matching record — the exact same JSON the
 * KMP client produces. `name()` is the stable key the whitelist checks.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Command.NaturalLanguage.class, name = "nl"),
        @JsonSubTypes.Type(value = Command.SetVolume.class, name = "set_volume"),
        @JsonSubTypes.Type(value = Command.Shutdown.class, name = "shutdown"),
        @JsonSubTypes.Type(value = Command.Reboot.class, name = "reboot"),
        @JsonSubTypes.Type(value = Command.LockScreen.class, name = "lock_screen"),
        @JsonSubTypes.Type(value = Command.KillProcess.class, name = "kill_process"),
        @JsonSubTypes.Type(value = Command.ListProcesses.class, name = "list_processes"),
        @JsonSubTypes.Type(value = Command.OpenUrl.class, name = "open_url"),
        @JsonSubTypes.Type(value = Command.OpenApp.class, name = "open_app"),
        @JsonSubTypes.Type(value = Command.Screenshot.class, name = "screenshot"),
        @JsonSubTypes.Type(value = Command.AgentTask.class, name = "agent_task"),
        @JsonSubTypes.Type(value = Command.Click.class, name = "click"),
        @JsonSubTypes.Type(value = Command.TypeText.class, name = "type_text"),
        @JsonSubTypes.Type(value = Command.Scroll.class, name = "scroll"),
        @JsonSubTypes.Type(value = Command.StartScreenStream.class, name = "start_screen_stream"),
        @JsonSubTypes.Type(value = Command.StopScreenStream.class, name = "stop_screen_stream"),
        @JsonSubTypes.Type(value = Command.GetClipboard.class, name = "get_clipboard"),
        @JsonSubTypes.Type(value = Command.SetClipboard.class, name = "set_clipboard"),
        @JsonSubTypes.Type(value = Command.ListFiles.class, name = "list_files"),
        @JsonSubTypes.Type(value = Command.SendFileToPhone.class, name = "send_file_to_phone"),
        @JsonSubTypes.Type(value = Command.SendFileToPc.class, name = "send_file_to_pc"),
        @JsonSubTypes.Type(value = Command.CancelTask.class, name = "cancel_task"),
        @JsonSubTypes.Type(value = Command.AnswerQuestion.class, name = "answer_question"),
        @JsonSubTypes.Type(value = Command.SetDisplayOption.class, name = "set_display_option"),
        @JsonSubTypes.Type(value = Command.QuickAction.class, name = "quick_action"),
        @JsonSubTypes.Type(value = Command.Sequence.class, name = "sequence"),
        @JsonSubTypes.Type(value = Command.DevRepos.class, name = "dev_repos")
})
public sealed interface Command permits
        Command.NaturalLanguage, Command.SetVolume, Command.Shutdown, Command.Reboot,
        Command.LockScreen, Command.KillProcess, Command.ListProcesses, Command.OpenUrl,
        Command.OpenApp, Command.Screenshot, Command.AgentTask, Command.Click,
        Command.TypeText, Command.Scroll, Command.StartScreenStream, Command.StopScreenStream,
        Command.GetClipboard, Command.SetClipboard,
        Command.ListFiles, Command.SendFileToPhone, Command.SendFileToPc,
        Command.CancelTask, Command.AnswerQuestion, Command.SetDisplayOption,
        Command.QuickAction, Command.Sequence, Command.DevRepos {

    /** Stable name used by the whitelist and audit log. */
    String name();

    // Layer 1: raw phrase, handled only by the relay
    record NaturalLanguage(String text) implements Command {
        public String name() { return "nl"; }
    }

    // Layer 2: concrete system commands
    record SetVolume(int level) implements Command {
        public String name() { return "set_volume"; }
    }
    record Shutdown(int delaySeconds) implements Command {
        public String name() { return "shutdown"; }
    }
    record Reboot(int delaySeconds) implements Command {
        public String name() { return "reboot"; }
    }
    record LockScreen() implements Command {
        public String name() { return "lock_screen"; }
    }
    record KillProcess(String processName) implements Command {
        public String name() { return "kill_process"; }
    }
    record ListProcesses() implements Command {
        public String name() { return "list_processes"; }
    }
    record OpenUrl(String url) implements Command {
        public String name() { return "open_url"; }
    }
    record OpenApp(String appName) implements Command {
        public String name() { return "open_app"; }
    }
    record Screenshot() implements Command {
        public String name() { return "screenshot"; }
    }

    // Layer 3: computer-use / vision agent
    record AgentTask(String goal, AgentScope scope, int maxSteps) implements Command {
        public String name() { return "agent_task"; }
    }
    record Click(int x, int y) implements Command {
        public String name() { return "click"; }
    }
    record TypeText(String text) implements Command {
        public String name() { return "type_text"; }
    }
    record Scroll(int dx, int dy) implements Command {
        public String name() { return "scroll"; }
    }

    // Layer 4: live screen streaming (reuses ScreenFrame.seq to tag stream frames)
    record StartScreenStream(int fps, int maxWidth) implements Command {
        public String name() { return "start_screen_stream"; }
    }
    record StopScreenStream() implements Command {
        public String name() { return "stop_screen_stream"; }
    }

    // Layer 5: буфер обмена
    record GetClipboard() implements Command {
        public String name() { return "get_clipboard"; }
    }
    record SetClipboard(String text) implements Command {
        public String name() { return "set_clipboard"; }
    }

    // Layer 6: передача файлов
    record ListFiles(String folder) implements Command {
        public String name() { return "list_files"; }
    }
    record SendFileToPhone(String folder, String fileName) implements Command {
        public String name() { return "send_file_to_phone"; }
    }
    record SendFileToPc(String folder, String fileName, String base64) implements Command {
        public String name() { return "send_file_to_pc"; }
    }

    // Layer 7: управление уже запущенной задачей
    record CancelTask() implements Command {
        public String name() { return "cancel_task"; }
    }
    /**
     * Ответ человека на AgentQuestion. Как и cancel_task, выполняется прямо в
     * потоке чтения сокета: поток задачи в это время занят — он ждёт этот ответ.
     */
    record AnswerQuestion(String questionId, String answer) implements Command {
        public String name() { return "answer_question"; }
    }
    /** Показ на экране ПК с телефона: option — только "xray" или "plan" (см. CommandExecutor). */
    record SetDisplayOption(String option, boolean enabled) implements Command {
        public String name() { return "set_display_option"; }
    }

    // Layer 8: быстрые действия и составные просьбы
    /**
     * Мгновенное действие без параметров-строк: action — строго из {@link #ACTIONS}.
     * amount нужен только для volume_delta (на сколько процентов, со знаком).
     */
    record QuickAction(String action, int amount) implements Command {
        /** Закрытый список: что не отсюда, агент не выполнит. */
        public static final java.util.Set<String> ACTIONS = java.util.Set.of(
                "play_pause", "next_track", "prev_track", "volume_delta", "minimize_all");
        public String name() { return "quick_action"; }
    }
    /**
     * Несколько простых команд из одной фразы («сделай потише и открой ютуб») —
     * агент выполняет их по очереди и отвечает одним итогом. Внутри — только
     * простые команды (не задачи ИИ-агенту и не другие sequence), не больше
     * {@link #MAX}; белый список проверяет каждую.
     */
    record Sequence(java.util.List<Command> commands) implements Command {
        public static final int MAX = 4;
        public String name() { return "sequence"; }
    }

    // Layer 9: проекты (git)
    /** Список разрешённых репозиториев и их состояние — для экрана «Проекты» на телефоне. Только чтение. */
    record DevRepos() implements Command {
        public String name() { return "dev_repos"; }
    }
}
