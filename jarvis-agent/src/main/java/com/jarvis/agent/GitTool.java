package com.jarvis.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git для агента — узкие операции status / pull / commit / push в
 * разрешённых репозиториях ({@link RepoCatalog}).
 *
 * <h2>Почему это не тот «терминал», который запрещён</h2>
 * Терминал запрещён ({@code ShellGuard}), потому что там модель пишет любую
 * команду. Здесь команду собирает КОД: программа {@code git}, фиксированные
 * аргументы, папка из списка человека. Модель может только выбрать операцию
 * из четырёх и написать текст сообщения коммита — а текст уходит в git через
 * стандартный ввод ({@code commit -F -}), то есть никогда не разбирается как
 * команда. Никакой оболочки (cmd, PowerShell) между Java и git нет.
 *
 * <h2>Защиты</h2>
 * <ul>
 *   <li>commit и push выполняются только после «Да» на телефоне, и человек
 *       перед этим видит, что именно уйдёт (см. {@link DevTools});</li>
 *   <li>файлы с секретами (.env, ключи, сертификаты) в коммит не попадают
 *       никогда — коммит отменяется целиком;</li>
 *   <li>git не может спросить пароль: {@code GIT_TERMINAL_PROMPT=0}. Нет
 *       входа в удалённый репозиторий — push честно падает с ошибкой, а не
 *       висит, ожидая ввода, которого никто не сделает;</li>
 *   <li>pull только «перемоткой» ({@code --ff-only}): если ветки разошлись,
 *       агент не устраивает слияние сам, а сообщает об этом человеку.</li>
 * </ul>
 */
public final class GitTool {

    private GitTool() {}

    /** Сколько ждать обычную локальную операцию и сетевую (pull/push). */
    private static final Duration LOCAL = Duration.ofSeconds(20);
    private static final Duration NETWORK = Duration.ofSeconds(90);
    /** Сколько строк диффа показывать на телефоне — больше там не прочитать. */
    private static final int PREVIEW_LINES = 30;

    /**
     * Файлы, которые нельзя коммитить ни при каких условиях. Шаблоны вида
     * «.env.example» разрешены: это образец без значений, его как раз коммитят.
     */
    private static final Pattern SECRET = Pattern.compile(
            "(^|/)(\\.env(\\.(?!example$|sample$|template$)[^/]+)?|id_rsa|id_ed25519|id_ecdsa"
            + "|[^/]+\\.(pem|key|pfx|p12|keystore|jks)|credentials(\\.json)?|token\\.properties|secrets?\\.[a-z]+)$",
            Pattern.CASE_INSENSITIVE);

    /** Итог вызова git: код возврата и весь вывод (stdout и stderr вместе). */
    public record Out(int code, String text) {
        boolean ok() { return code == 0; }
    }

    /** Что попало в индекс перед коммитом — это и показывается человеку. */
    public record Staged(List<String> files, String stat, String preview) {
        public boolean isEmpty() { return files.isEmpty(); }
    }

    // --- операции ------------------------------------------------------------

    /** «ветка main, впереди origin/main на 1; изменено 2: docker-compose.yml, index.html». */
    public static String status(Path repo) throws IOException {
        Out out = must(git(repo, LOCAL, null, "status", "--porcelain=v1", "-b"), "git status");
        String[] lines = out.text().split("\\R");
        String branch = lines.length > 0 && lines[0].startsWith("## ") ? describeBranch(lines[0].substring(3)) : "ветка ?";
        List<String> files = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].length() > 3) files.add(lines[i].substring(3).strip());
        }
        return branch + "; " + (files.isEmpty() ? "изменений нет"
                : "изменено " + files.size() + ": " + shortList(files));
    }

    /** Подтянуть изменения с сервера — только перемоткой, без слияний. */
    public static String pull(Path repo) throws IOException {
        Out out = git(repo, NETWORK, null, "pull", "--ff-only");
        if (!out.ok()) {
            String t = out.text().toLowerCase(Locale.ROOT);
            if (t.contains("not possible to fast-forward") || t.contains("diverg")) {
                throw new IllegalStateException("ветки разошлись: сам сливать не буду, нужен человек");
            }
            throw new IllegalStateException("git pull не удался: " + lastLines(out.text(), 3));
        }
        return out.text().contains("Already up to date") ? "уже актуально" : "обновлено: " + lastLines(out.text(), 2);
    }

    /**
     * Добавить в индекс все изменения (как {@code git add -A}) и описать их.
     * Если человек откажется, вызывающий обязан вызвать {@link #unstage}.
     */
    public static Staged stageAll(Path repo) throws IOException {
        must(git(repo, LOCAL, null, "add", "-A"), "git add");
        Out names = must(git(repo, LOCAL, null, "diff", "--cached", "--name-only"), "git diff");
        List<String> files = names.text().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (files.isEmpty()) return new Staged(List.of(), "", "");
        String stat = must(git(repo, LOCAL, null, "diff", "--cached", "--stat=72"), "git diff --stat").text().strip();
        String diff = must(git(repo, LOCAL, null, "diff", "--cached", "-U1", "--no-color"), "git diff").text();
        return new Staged(files, stat, preview(diff));
    }

    /** Вернуть индекс как был (файлы на диске не трогаются). */
    public static void unstage(Path repo) {
        try {
            git(repo, LOCAL, null, "reset", "-q");
        } catch (IOException ignored) {
            // не вышло — изменения просто остались в индексе, на диске всё цело
        }
    }

    /** Файлы с секретами среди перечисленных. */
    public static List<String> secretFiles(List<String> files) {
        return files.stream().filter(f -> SECRET.matcher(f.replace('\\', '/')).find()).toList();
    }

    /** Коммит того, что уже в индексе. Сообщение идёт через стандартный ввод. */
    public static String commit(Path repo, String message) throws IOException {
        String msg = message == null || message.isBlank() ? "Изменения с телефона («Фауст»)" : message.strip();
        must(git(repo, LOCAL, msg, "commit", "-q", "-F", "-"), "git commit");
        String head = must(git(repo, LOCAL, null, "log", "-1", "--format=%h %s"), "git log").text().strip();
        return "коммит " + head;
    }

    /** Коммиты, которые уйдут при push (до 10) — их видит человек перед «Да». */
    public static List<String> pendingPush(Path repo) throws IOException {
        Out out = hasUpstream(repo)
                ? git(repo, LOCAL, null, "log", "--format=%h %s", "@{u}..HEAD")
                : git(repo, LOCAL, null, "log", "--format=%h %s", "-10");
        return out.ok() ? out.text().lines().map(String::strip).filter(s -> !s.isEmpty()).limit(10).toList() : List.of();
    }

    /** Отправить текущую ветку на сервер. Новую ветку — с привязкой к origin. */
    public static String push(Path repo) throws IOException {
        String branch = currentBranch(repo);
        boolean upstream = hasUpstream(repo);
        Out out = upstream ? git(repo, NETWORK, null, "push")
                           : git(repo, NETWORK, null, "push", "-u", "origin", "HEAD");
        if (!out.ok()) {
            String t = out.text().toLowerCase(Locale.ROOT);
            if (t.contains("rejected") || t.contains("fetch first") || t.contains("non-fast-forward")) {
                throw new IllegalStateException("сервер отклонил push: там есть новые коммиты — сначала git pull");
            }
            if (t.contains("authentication") || t.contains("could not read username") || t.contains("permission denied")) {
                throw new IllegalStateException("нет входа в удалённый репозиторий — войдите в git на этом ПК вручную");
            }
            throw new IllegalStateException("git push не удался: " + lastLines(out.text(), 3));
        }
        return "отправлено: " + branch + " → " + (upstream ? upstreamName(repo) : "origin/" + branch);
    }

    /** Сведения для экрана «Проекты»: ветка, что изменено, что не отправлено, последний коммит. */
    public static com.jarvis.agent.protocol.ResultData.RepoInfo info(String name, Path repo) {
        try {
            Out st = must(git(repo, LOCAL, null, "status", "--porcelain=v1", "-b"), "git status");
            String[] lines = st.text().split("\\R");
            String header = lines.length > 0 && lines[0].startsWith("## ") ? lines[0].substring(3).strip() : "";
            List<String> changed = new ArrayList<>();
            for (int i = 1; i < lines.length && changed.size() < 30; i++) {
                if (lines[i].length() > 3) changed.add(lines[i].substring(0, 2).strip() + " " + lines[i].substring(3).strip());
            }
            String branch = header.startsWith("No commits yet on ") ? header.substring(18)
                    : header.split("\\.\\.\\.", 2)[0].split(" ", 2)[0];
            int ahead = intOr(number(header, "ahead"), 0), behind = intOr(number(header, "behind"), 0);
            Out last = git(repo, LOCAL, null, "log", "-1", "--format=%h %s%x1f%ct");
            String lastCommit = null;
            if (last.ok() && last.text().contains("\u001f")) {
                String[] p = last.text().strip().split("\u001f", 2);
                lastCommit = p[0] + " · " + ago(Long.parseLong(p[1].strip()));
            }
            boolean hasRemote = !git(repo, LOCAL, null, "remote").text().isBlank();
            return new com.jarvis.agent.protocol.ResultData.RepoInfo(name, branch, changed, ahead, behind,
                    lastCommit, hasRemote, null);
        } catch (Exception e) {
            return new com.jarvis.agent.protocol.ResultData.RepoInfo(name, "?", List.of(), 0, 0, null, false,
                    e.getMessage());
        }
    }

    /** Последние коммиты: «a1b2c3d Порт 8090 (Рамиз, 2 ч назад)». */
    public static List<String> log(Path repo, int n) throws IOException {
        Out out = must(git(repo, LOCAL, null, "log", "-" + Math.max(1, Math.min(30, n)),
                "--format=%h %s%x1f%an%x1f%ct"), "git log");
        List<String> rows = new ArrayList<>();
        for (String line : out.text().split("\\R")) {
            String[] p = line.split("\u001f");
            if (p.length == 3) rows.add(p[0] + " (" + p[1] + ", " + ago(Long.parseLong(p[2].strip())) + ")");
        }
        return rows;
    }

    /**
     * Перейти на ветку; если её нет — создать от текущей. Имя проверяет сам git
     * ({@code check-ref-format}): модель не протащит в него ни пробелов, ни «..».
     * Несохранённые правки git переносит на новую ветку сам; если они мешают
     * переключению на существующую, git откажет — и агент скажет об этом.
     */
    public static String branch(Path repo, String name) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.startsWith("-") || !git(repo, LOCAL, null, "check-ref-format", "--branch", n).ok()) {
            throw new IllegalArgumentException("недопустимое имя ветки «" + n + "»");
        }
        boolean exists = git(repo, LOCAL, null, "rev-parse", "--verify", "--quiet", "refs/heads/" + n).ok();
        Out out = exists ? git(repo, LOCAL, null, "checkout", "-q", n) : git(repo, LOCAL, null, "checkout", "-q", "-b", n);
        if (!out.ok()) throw new IllegalStateException("не удалось перейти на ветку " + n + ": " + lastLines(out.text(), 2));
        return (exists ? "переключено на ветку " : "создана ветка ") + n;
    }

    /** Отслеживаемые файлы с несохранёнными правками — то, что откатит {@link #discard}. */
    public static List<String> discardable(Path repo) throws IOException {
        Out out = must(git(repo, LOCAL, null, "diff", "HEAD", "--name-only"), "git diff");
        return out.text().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Вернуть отслеживаемые файлы к последнему коммиту. Новые (неотслеживаемые)
     * файлы НЕ удаляются: их нет в git, и вернуть их потом было бы неоткуда.
     */
    public static String discard(Path repo) throws IOException {
        List<String> files = discardable(repo);
        if (files.isEmpty()) return "откатывать нечего";
        must(git(repo, LOCAL, null, "restore", "--staged", "--worktree", "--source=HEAD", "--", "."), "git restore");
        return "откачено файлов: " + files.size() + " (" + shortList(files) + ")";
    }

    /** «5 мин назад», «2 ч назад», «3 дн назад». */
    static String ago(long epochSec) {
        long s = Math.max(0, System.currentTimeMillis() / 1000 - epochSec);
        if (s < 60) return "только что";
        if (s < 3600) return (s / 60) + " мин назад";
        if (s < 86400) return (s / 3600) + " ч назад";
        return (s / 86400) + " дн назад";
    }

    private static int intOr(String s, int dflt) {
        return s == null ? dflt : Integer.parseInt(s);
    }

    public static String currentBranch(Path repo) throws IOException {
        return must(git(repo, LOCAL, null, "rev-parse", "--abbrev-ref", "HEAD"), "git rev-parse").text().strip();
    }

    // --- запуск git --------------------------------------------------------------

    /**
     * Запустить git в папке репозитория.
     *
     * @param stdin текст на стандартный ввод (сообщение коммита) или null
     */
    static Out git(Path repo, Duration timeout, String stdin, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git",
                // Русские имена файлов — как есть, а не «\320\264\320\270...».
                "-c", "core.quotepath=false",
                "-c", "color.ui=false",
                "-C", repo.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");     // не спрашивать логин/пароль в консоли
        pb.environment().put("GCM_INTERACTIVE", "never");     // и не открывать окно входа Git Credential Manager
        pb.environment().put("GIT_EDITOR", "true");           // и не открывать редактор
        pb.environment().put("LC_ALL", "C");                  // сообщения git по-английски: их разбирает код
        Process p = pb.start();
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream s = p.getInputStream()) {
                s.transferTo(buf);
            } catch (IOException ignored) {
                // процесс закрылся
            }
        }, "git-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " не ответил за " + timeout.toSeconds() + " с");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
        return new Out(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
    }

    private static Out must(Out out, String what) {
        if (!out.ok()) throw new IllegalStateException(what + ": " + lastLines(out.text(), 3));
        return out;
    }

    private static boolean hasUpstream(Path repo) throws IOException {
        return git(repo, LOCAL, null, "rev-parse", "--abbrev-ref", "@{u}").ok();
    }

    private static String upstreamName(Path repo) throws IOException {
        Out out = git(repo, LOCAL, null, "rev-parse", "--abbrev-ref", "@{u}");
        return out.ok() ? out.text().strip() : "?";
    }

    /** «main...origin/main [ahead 1, behind 2]» -> «ветка main, впереди origin/main на 1, позади на 2». */
    static String describeBranch(String header) {
        String h = header.strip();
        if (h.startsWith("No commits yet on ")) return "ветка " + h.substring(18) + " (ещё без коммитов)";
        String counts = "";
        int br = h.indexOf(" [");
        if (br >= 0) {
            counts = h.substring(br + 2, h.length() - (h.endsWith("]") ? 1 : 0));
            h = h.substring(0, br);
        }
        String[] parts = h.split("\\.\\.\\.", 2);
        StringBuilder sb = new StringBuilder("ветка ").append(parts[0]);
        if (parts.length < 2) return sb.append(" (не связана с сервером)").toString();
        String ahead = number(counts, "ahead"), behind = number(counts, "behind");
        if (ahead == null && behind == null) return sb.append(", совпадает с ").append(parts[1]).toString();
        sb.append(", относительно ").append(parts[1]).append(":");
        if (ahead != null) sb.append(" не отправлено коммитов — ").append(ahead);
        if (ahead != null && behind != null) sb.append(",");
        if (behind != null) sb.append(" не скачано — ").append(behind);
        return sb.toString();
    }

    private static String number(String counts, String word) {
        java.util.regex.Matcher m = Pattern.compile(word + " (\\d+)").matcher(counts);
        return m.find() ? m.group(1) : null;
    }

    /** Первые строки диффа без служебных «diff --git / index», каждая не длиннее 100 символов. */
    static String preview(String diff) {
        List<String> out = new ArrayList<>();
        for (String line : diff.split("\\R")) {
            if (line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("--- ")
                || line.startsWith("new file mode") || line.startsWith("deleted file mode")) continue;
            if (line.startsWith("+++ ")) line = "▸ " + line.substring(4).replaceFirst("^b/", "");
            out.add(line.length() > 100 ? line.substring(0, 99) + "…" : line);
            if (out.size() >= PREVIEW_LINES) {
                out.add("… (дифф обрезан)");
                break;
            }
        }
        return String.join("\n", out);
    }

    private static String shortList(List<String> files) {
        return files.size() <= 5 ? String.join(", ", files)
                : String.join(", ", files.subList(0, 5)) + " и ещё " + (files.size() - 5);
    }

    private static String lastLines(String text, int n) {
        List<String> lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }
}
