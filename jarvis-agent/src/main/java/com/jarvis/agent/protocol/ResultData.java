package com.jarvis.agent.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** Optional typed payload attached to a CommandResult. Mirrors the Kotlin side. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ResultData.ProcessList.class, name = "process_list"),
        @JsonSubTypes.Type(value = ResultData.AgentReport.class, name = "agent_report"),
        @JsonSubTypes.Type(value = ResultData.ClipboardText.class, name = "clipboard_text"),
        @JsonSubTypes.Type(value = ResultData.FileList.class, name = "file_list"),
        @JsonSubTypes.Type(value = ResultData.FileTransfer.class, name = "file_transfer"),
        @JsonSubTypes.Type(value = ResultData.RepoList.class, name = "repo_list"),
        @JsonSubTypes.Type(value = ResultData.TaskReport.class, name = "task_report")
})
public sealed interface ResultData permits ResultData.ProcessList, ResultData.AgentReport,
        ResultData.ClipboardText, ResultData.FileList, ResultData.FileTransfer, ResultData.RepoList,
        ResultData.TaskReport {

    record ProcessList(List<ProcessInfo> processes) implements ResultData {}

    record AgentReport(boolean completed, int stepsTaken, List<String> trace) implements ResultData {}

    record ClipboardText(String text) implements ResultData {}

    record FileList(List<FileEntry> entries) implements ResultData {}

    record FileTransfer(String fileName, String base64, long sizeBytes) implements ResultData {}

    /** Результат dev_repos: разрешённые git-репозитории и их состояние (экран «Проекты»). */
    record RepoList(List<RepoInfo> repos) implements ResultData {}

    /**
     * Один репозиторий — без пути на диске: телефону он не нужен.
     *
     * @param changed    изменённые и новые файлы (не больше 30)
     * @param ahead      сколько коммитов ещё не отправлено на сервер
     * @param behind     сколько коммитов на сервере ещё не скачано (по последним данным, без fetch)
     * @param lastCommit «a1b2c3d Порт 8090 · 2 ч назад»
     * @param error      что не так с репозиторием, или null
     */
    record RepoInfo(String name, String branch, List<String> changed, int ahead, int behind,
                    String lastCommit, boolean hasRemote, String error) {}

    /**
     * Результат get_task_report: кадры шагов задачи с уже нарисованными
     * отметками. caption — тот же текст, что на кадре, отдельно (озвучка, TalkBack).
     */
    record TaskReport(String taskId, String goal, boolean completed, List<ReportFrame> frames) implements ResultData {}

    /** Один кадр отчёта: step 0 — титульный, -1 — итоговый; base64 — JPEG. */
    record ReportFrame(int step, String caption, String base64) {}

    /** Not a ResultData itself — just a row inside ProcessList. */
    record ProcessInfo(long pid, String name) {}

    /** Not a ResultData itself — просто строка внутри FileList (как ProcessInfo у ProcessList). */
    record FileEntry(String name, long sizeBytes) {}
}
