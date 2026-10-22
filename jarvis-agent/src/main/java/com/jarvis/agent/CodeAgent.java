package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Внешний агент для кода — небольшие правки в разрешённом репозитории
 * («поменяй порт в docker-compose на 8090», «добавь переменную окружения»).
 *
 * <h2>Почему внешний, а не свой</h2>
 * Править код по смыслу — задача другого масштаба, чем разбор фразы или клик
 * по кнопке: нужно прочитать проект, найти место, аккуратно изменить. Для
 * этого есть готовые агенты (Claude Code, Gemini CLI, Aider), и писать свой
 * было бы хуже и дольше. «Фауст» здесь — диспетчер: он решает, ГДЕ агенту
 * можно работать, ЧТО ему можно делать, и показывает человеку итог.
 * Какая программа делает правку, задаёт настройка {@code code.command}.
 *
 * <h2>Что агенту для кода разрешено</h2>
 * Только читать и править файлы ({@code --tools Read,Edit,Write,Glob,Grep}).
 * Терминала у него нет вовсе — не «запрещён правилом», а не выдан: он не может
 * ни запустить сборку, ни сделать push, ни сходить в интернет. Работает он в
 * папке репозитория из {@link RepoCatalog}, а за её пределы Claude Code сам не
 * пишет. Коммит и push делает уже {@link GitTool} — после «Да» человека, который
 * видит дифф.
 *
 * <h2>Где ключ</h2>
 * Это осознанное исключение из правила «ключ модели только на релее»: агент для
 * кода работает под учётной записью самого пользователя на его же ПК (он один
 * раз входит в Claude Code). В коде «Фауста» и в настройках ключа нет.
 */
public final class CodeAgent {

    private CodeAgent() {}

    /** Включён ли агент для кода (code.enabled=false — шаг «правка кода» сразу отказывает). */
    public static final String ENABLED = "code.enabled";
    /** Чем запускать: по умолчанию «claude» из PATH; можно полный путь к claude.exe. */
    public static final String COMMAND = "code.command";
    /** Сколько ждать одну правку, секунд (по умолчанию 300). */
    public static final String TIMEOUT_SEC = "code.timeoutSec";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Итог правки.
     *
     * @param changed файлы, которые реально изменились (по git, а не со слов модели)
     * @param said    что сказал агент для кода
     * @param costUsd сколько стоил запрос (если программа это сообщает), иначе null
     */
    public record Result(List<String> changed, String said, Double costUsd, String engine) {}

    /**
     * Каким редактором править: {@code auto} (по умолчанию) — Claude Code, а если
     * он не установлен или не вошёл в аккаунт — встроенный на Gemini через релей;
     * {@code claude} — только Claude Code; {@code gemini} — только встроенный.
     */
    public static final String ENGINE = "code.engine";

    /** До какого момента не пробовать Claude Code после отказа (не вошёл / нет программы). */
    private static volatile long claudeDownUntil = 0;

    /**
     * Выполнить правку подходящим редактором.
     *
     * @param llm запрос к модели через релей — для встроенного редактора
     * @throws IllegalStateException агент выключен или ни один редактор не справился
     */
    public static Result edit(RepoCatalog.Repo repo, String task, GeminiCodeEditor.Llm llm) throws Exception {
        if (!AgentSettings.flag(ENABLED, true)) {
            throw new IllegalStateException("агент для кода выключен (code.enabled=false в agent.properties)");
        }
        String engine = AgentSettings.text(ENGINE, "auto").toLowerCase(java.util.Locale.ROOT);
        String claudeProblem = null;
        if (!engine.equals("gemini") && (engine.equals("claude") || System.currentTimeMillis() >= claudeDownUntil)) {
            try {
                return withClaude(repo, task);
            } catch (ClaudeUnavailable e) {
                if (engine.equals("claude")) throw new IllegalStateException(e.getMessage());
                // Не вошёл или не установлен — это не поправится за секунды: десять
                // минут не тратим время на заведомо неудачный запуск.
                claudeDownUntil = System.currentTimeMillis() + 10 * 60_000L;
                claudeProblem = e.getMessage();
            }
        }
        if (llm == null) throw new IllegalStateException(claudeProblem != null ? claudeProblem : "нет связи с моделью");
        GeminiCodeEditor.Edit e = new GeminiCodeEditor(llm).edit(repo, task);
        return new Result(e.changed(), e.summary(), null, "встроенный редактор");
    }

    /** Claude Code не может работать вообще (не установлен, не вошёл) — повод взять другой редактор. */
    static final class ClaudeUnavailable extends Exception {
        ClaudeUnavailable(String message) {
            super(message);
        }
    }

    /**
     * Правка через Claude Code.
     *
     * @throws ClaudeUnavailable    программы нет или она не вошла в аккаунт
     * @throws IllegalStateException программа работала, но правка не удалась
     */
    static Result withClaude(RepoCatalog.Repo repo, String task) throws Exception {
        Map<String, String> before = snapshot(repo);

        List<String> cmd = new ArrayList<>(List.of(command(), "-p",
                "--tools", "Read,Edit,Write,Glob,Grep",
                // Секреты агенту для кода и читать незачем.
                "--disallowedTools", "Read(./.env)", "Read(./**/.env)", "Edit(./.env)", "Write(./.env)",
                "--permission-mode", "acceptEdits",
                "--output-format", "json",
                "--no-session-persistence"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.path().toFile());
        // Ошибки claude пишет в stderr, ответ в stdout — разделяем, чтобы JSON не испортился.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new ClaudeUnavailable("Claude Code не найден («" + command() + "»)");
        }
        // Задачу — через стандартный ввод, а не аргументом: в командной строке
        // Windows кавычки и кириллица ломаются, а ввод передаётся как есть.
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt(repo, task).getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream s = p.getInputStream()) {
                s.transferTo(buf);
            } catch (IOException ignored) {
                // процесс закрылся
            }
        }, "code-agent-output");
        reader.setDaemon(true);
        reader.start();
        int timeout = AgentSettings.number(TIMEOUT_SEC, 300, 30, 1800);
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("агент для кода не уложился в " + timeout + " с — правка прервана "
                                            + "(что успел изменить, видно в git status)");
        }
        reader.join(2000);

        Reply r = parse(buf.toString(StandardCharsets.UTF_8));
        if (r.error) {
            String why = explain(r.said);
            if (why.contains("не вошёл")) throw new ClaudeUnavailable(why);
            throw new IllegalStateException(why);
        }
        Map<String, String> after = snapshot(repo);
        List<String> changed = new ArrayList<>(after.entrySet().stream()
                .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                .map(Map.Entry::getKey)
                .toList());
        // Файл, который был изменён до правки, а агент вернул его к последнему
        // коммиту, из списка git status пропадает — но это тоже правка агента.
        before.keySet().stream().filter(f -> !after.containsKey(f)).forEach(changed::add);
        return new Result(List.copyOf(changed), r.said, r.cost, "Claude Code");
    }

    /** Задача для агента для кода — с рамками, которые он должен соблюдать. */
    static String prompt(RepoCatalog.Repo repo, String task) {
        return "Ты работаешь в репозитории «" + repo.name() + "» (текущая папка).\n"
               + "Задача пришла голосом с телефона пользователя: " + task.strip() + "\n\n"
               + "Правила:\n"
               + "- меняй только то, что нужно для задачи, и как можно меньше;\n"
               + "- не трогай файлы с секретами (.env, ключи, пароли);\n"
               + "- команды запускать нельзя — только читать и править файлы; коммит не делай;\n"
               + "- если задача неясна или опасна — ничего не меняй и объясни почему.\n"
               + "В конце ответь одной-двумя короткими фразами по-русски: что изменено и где.";
    }

    private record Reply(boolean error, String said, Double cost) {}

    /** Разбор ответа claude --output-format json. Не JSON — значит, программа упала раньше ответа. */
    static Reply parse(String stdout) {
        String text = stdout == null ? "" : stdout.strip();
        int start = text.indexOf('{');
        if (start < 0) return new Reply(true, text.isEmpty() ? "пустой ответ" : text, null);
        try {
            JsonNode n = MAPPER.readTree(text.substring(start));
            boolean err = n.path("is_error").asBoolean(false);
            String said = n.path("result").asText("").strip();
            Double cost = n.hasNonNull("total_cost_usd") ? n.get("total_cost_usd").asDouble() : null;
            return new Reply(err, said, cost);
        } catch (IOException e) {
            return new Reply(true, "непонятный ответ: " + text.substring(0, Math.min(200, text.length())), null);
        }
    }

    /** Ошибку агента для кода — понятными словами и с тем, что делать. */
    static String explain(String said) {
        String s = said == null ? "" : said.toLowerCase(Locale.ROOT);
        if (s.contains("authenticate") || s.contains("oauth") || s.contains("login") || s.contains("api key")) {
            return "агент для кода не вошёл в аккаунт: на ПК запустите claude и выполните /login";
        }
        if (s.contains("rate limit") || s.contains("usage limit") || s.contains("quota")) {
            return "у агента для кода исчерпан лимит запросов: " + said;
        }
        return "агент для кода не справился: " + said;
    }

    private static String command() {
        return AgentSettings.text(COMMAND, "claude");
    }

    /**
     * Что сейчас отличается от последнего коммита: файл -> отпечаток его содержимого.
     *
     * <p>Зачем отпечатки, а не просто список из git status: в репозитории могли
     * быть несохранённые правки ещё ДО агента. Список «изменённых» тогда
     * одинаков до и после, и не видно, тронул ли агент эти файлы. Отпечаток
     * содержимого ({@code git hash-object}) меняется при любой правке. Так
     * «что изменил агент» — это факт с диска, а не рассказ модели.
     */
    static Map<String, String> snapshot(RepoCatalog.Repo repo) throws IOException {
        Duration t = Duration.ofSeconds(20);
        GitTool.Out out = GitTool.git(repo.path(), t, null, "status", "--porcelain=v1", "-uall");
        Map<String, String> map = new TreeMap<>();
        if (!out.ok()) return map;
        for (String line : out.text().split("\\R")) {
            if (line.length() <= 3) continue;
            String file = line.substring(3).strip();
            int arrow = file.indexOf(" -> ");               // переименование: берём новое имя
            if (arrow >= 0) file = file.substring(arrow + 4);
            if (!Files.isRegularFile(repo.path().resolve(file))) {
                map.put(file, "удалён");
                continue;
            }
            GitTool.Out h = GitTool.git(repo.path(), t, null, "hash-object", "--", file);
            map.put(file, h.ok() ? h.text().strip() : "?");
        }
        return map;
    }
}
