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

    /** До какого момента не пробовать Claude Code после отказа (не вошёл / нет программы / лимит). */
    private static volatile long claudeDownUntil = 0;

    /** Для тестов: забыть прошлые отказы Claude Code. */
    static void resetPause() {
        claudeDownUntil = 0;
    }

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
        // Отпечаток ДО любой правки: если Claude Code успел что-то изменить и
        // завис, а доделал встроенный редактор, «что изменено» должно включать
        // правки обоих — человек увидит их в одном диффе при коммите.
        Map<String, String> before = snapshot(repo);
        ClaudeUnavailable claudeProblem = null;
        if (!engine.equals("gemini") && (engine.equals("claude") || System.currentTimeMillis() >= claudeDownUntil)) {
            try {
                return withClaude(repo, task);
            } catch (ClaudeUnavailable e) {
                if (engine.equals("claude")) throw new IllegalStateException(e.getMessage());
                // Не вошёл, нет программы, кончился лимит — это не поправится за
                // секунды: какое-то время не тратим время на заведомо неудачный
                // запуск. Зависание и сбой — случайность, следующую правку снова
                // пробуем через Claude Code (pauseMs = 0).
                if (e.pauseMs > 0) claudeDownUntil = System.currentTimeMillis() + e.pauseMs;
                claudeProblem = e;
            }
        }
        if (llm == null) {
            throw new IllegalStateException(claudeProblem != null ? claudeProblem.getMessage() : "нет связи с моделью");
        }
        GeminiCodeEditor.Edit e = new GeminiCodeEditor(llm).edit(repo, task);
        if (claudeProblem == null) return new Result(e.changed(), e.summary(), null, "встроенный редактор");
        System.err.println("агент для кода: " + claudeProblem.getMessage() + " — правку сделал встроенный редактор");
        return new Result(changedSince(repo, before), e.summary(), null,
                "встроенный редактор, т.к. " + claudeProblem.shortReason);
    }

    /**
     * Claude Code сейчас не может сделать правку — повод взять другой редактор.
     * Сюда НЕ относится случай, когда Claude Code поработал и сам отказался
     * («задача опасна», «не нашёл, что менять»): это осмысленный ответ, и
     * отдавать ту же задачу другой модели в обход отказа нельзя.
     */
    static final class ClaudeUnavailable extends Exception {
        /** Сколько не пробовать Claude Code снова, мс; 0 — пробовать уже в следующий раз. */
        final long pauseMs;
        /** Причина в двух-трёх словах — для строки итога на телефоне. */
        final String shortReason;

        ClaudeUnavailable(String message, String shortReason, long pauseMs) {
            super(message);
            this.shortReason = shortReason;
            this.pauseMs = pauseMs;
        }
    }

    private static final long NOT_LOGGED_PAUSE_MS = 10 * 60_000L;
    private static final long LIMIT_PAUSE_MS = 30 * 60_000L;

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
            throw new ClaudeUnavailable("Claude Code не найден («" + command() + "»)", "Claude Code не установлен",
                    NOT_LOGGED_PAUSE_MS);
        }
        // Задачу — через стандартный ввод, а не аргументом: в командной строке
        // Windows кавычки и кириллица ломаются, а ввод передаётся как есть.
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt(repo, task).getBytes(StandardCharsets.UTF_8));
        } catch (IOException closedEarly) {
            // Программа закрылась, не дочитав задачу (сразу упала, нет входа).
            // Это не ошибка шага сама по себе: что случилось — скажет её ответ ниже.
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
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            // Зависание — не отказ по существу: задачу можно отдать запасному
            // редактору. Что Claude Code успел изменить, остаётся на диске и
            // попадёт в общий дифф (см. edit).
            throw new ClaudeUnavailable("Claude Code не ответил за " + timeout + " с",
                    "Claude Code не ответил за " + timeout + " с", 0);
        }
        reader.join(2000);

        Reply r = parse(buf.toString(StandardCharsets.UTF_8));
        if (r.error) {
            String why = explain(r.said);
            switch (trouble(r)) {
                case NOT_LOGGED_IN -> throw new ClaudeUnavailable(why, "Claude Code не вошёл в аккаунт", NOT_LOGGED_PAUSE_MS);
                case NO_SUBSCRIPTION -> throw new ClaudeUnavailable(why, "Claude Code не может использовать подписку",
                        LIMIT_PAUSE_MS);
                case LIMIT -> throw new ClaudeUnavailable(why, "у Claude Code кончился лимит", LIMIT_PAUSE_MS);
                case BROKEN -> throw new ClaudeUnavailable("Claude Code завершился без ответа: " + r.said,
                        "Claude Code завершился без ответа", 0);
                default -> throw new IllegalStateException(why);
            }
        }
        return new Result(changedSince(repo, before), r.said, r.cost, "Claude Code");
    }

    /**
     * Какие файлы изменились с момента отпечатка {@code before} — по git, а не
     * со слов модели.
     */
    static List<String> changedSince(RepoCatalog.Repo repo, Map<String, String> before) throws IOException {
        Map<String, String> after = snapshot(repo);
        List<String> changed = new ArrayList<>(after.entrySet().stream()
                .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                .map(Map.Entry::getKey)
                .toList());
        // Файл, который был изменён до правки, а агент вернул его к последнему
        // коммиту, из списка git status пропадает — но это тоже правка агента.
        before.keySet().stream().filter(f -> !after.containsKey(f)).forEach(changed::add);
        return List.copyOf(changed);
    }

    /** Почему Claude Code вернул ошибку — от этого зависит, звать ли запасной редактор. */
    enum Trouble { NOT_LOGGED_IN, NO_SUBSCRIPTION, LIMIT, BROKEN, REFUSED }

    static Trouble trouble(Reply r) {
        if (!r.json) return Trouble.BROKEN;          // упал раньше ответа — не по существу задачи
        String s = r.said == null ? "" : r.said.toLowerCase(Locale.ROOT);
        // Раньше «входа»: в этом сообщении тоже есть слова «API key» («…or use an
        // Anthropic API key instead»), а причина другая — вход есть, а подписку
        // использовать нельзя (замер 11.11: 403 oauth_not_allowed_for_organization,
        // «usually because a payment is past due»). /login тут не поможет.
        if (s.contains("subscription") || s.contains("billing") || s.contains("payment")) {
            return Trouble.NO_SUBSCRIPTION;
        }
        if (s.contains("authenticate") || s.contains("oauth") || s.contains("login") || s.contains("api key")) {
            return Trouble.NOT_LOGGED_IN;
        }
        if (LIMIT_WORDS.matcher(s).find()) return Trouble.LIMIT;
        return Trouble.REFUSED;
    }

    /** Как Claude Code и сервер сообщают о лимите и перегрузке (сообщения со временем менялись). */
    private static final java.util.regex.Pattern LIMIT_WORDS = java.util.regex.Pattern.compile(
            "rate limit|usage limit|limit reached|hit your limit|quota|overloaded|\\b529\\b|\\b429\\b");

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

    /** @param json ответ разобран как JSON (false — программа упала раньше ответа) */
    record Reply(boolean error, String said, Double cost, boolean json) {}

    /** Разбор ответа claude --output-format json. Не JSON — значит, программа упала раньше ответа. */
    static Reply parse(String stdout) {
        String text = stdout == null ? "" : stdout.strip();
        int start = text.indexOf('{');
        if (start < 0) return new Reply(true, text.isEmpty() ? "пустой ответ" : text, null, false);
        try {
            JsonNode n = MAPPER.readTree(text.substring(start));
            boolean err = n.path("is_error").asBoolean(false);
            String said = n.path("result").asText("").strip();
            Double cost = n.hasNonNull("total_cost_usd") ? n.get("total_cost_usd").asDouble() : null;
            return new Reply(err, said, cost, true);
        } catch (IOException e) {
            return new Reply(true, "непонятный ответ: " + text.substring(0, Math.min(200, text.length())), null, false);
        }
    }

    /** Ошибку агента для кода — понятными словами и с тем, что делать. */
    static String explain(String said) {
        Trouble t = trouble(new Reply(true, said, null, true));
        if (t == Trouble.NOT_LOGGED_IN) {
            return "агент для кода не вошёл в аккаунт: на ПК запустите claude и выполните /login";
        }
        if (t == Trouble.NO_SUBSCRIPTION) {
            return "Claude Code не может работать по подписке claude.ai (обычно — не прошла оплата): "
                   + "проверьте claude.ai/settings/billing. Ответ: " + said;
        }
        if (t == Trouble.LIMIT) return "у агента для кода исчерпан лимит запросов: " + said;
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
