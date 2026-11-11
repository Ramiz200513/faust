package com.jarvis.agent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Разрешённые команды репозиториев — тесты, сборка, проверка стиля. Человек
 * записывает их сам, агент запускает только по названию.
 *
 * <h2>Зачем, если есть доверенные скрипты</h2>
 * Скрипт из {@link ScriptCatalog} — «кнопка на всём ПК»: он не знает, в каком
 * проекте работает. Команда репозитория привязана к проекту: «тесты» в «демо»
 * и «тесты» в «диплом» — разные команды, и запускаются они в папке своего
 * проекта. Это и нужно циклу «правка → тесты → коммит»: после правки агент
 * для кода должен проверить именно тот проект, который правил.
 *
 * <h2>Почему это не терминал</h2>
 * Терминал запрещён, потому что там МОДЕЛЬ пишет команду. Здесь модель
 * называет только название из списка («тесты»): саму командную строку, её
 * аргументы и папку задал человек в файле {@code ~/.jarvis-agent/repo-commands.txt}.
 * Ни одного символа от модели в команду не попадает — даже вывод упавших
 * тестов, который потом уходит агенту для кода, идёт ему как текст задачи,
 * а не как команда.
 *
 * <h2>Формат файла</h2>
 * <pre>
 *   демо: тесты  = powershell -NoProfile -File tests\check.ps1
 *   диплом: тесты = gradlew.bat test
 *   диплом: сборка = gradlew.bat assembleDebug
 * </pre>
 * Название репозитория — как в {@code repos.txt}. Строка без репозитория из
 * {@code repos.txt} пропускается: команде негде запускаться.
 */
public final class RepoCommands {

    private RepoCommands() {}

    /** Сколько ждать одну команду, секунд (по умолчанию 600: сборка Android идёт минуты). */
    public static final String TIMEOUT_SEC = "code.commandTimeoutSec";

    /** Сколько последних строк вывода помнить: агенту для кода нужен текст ошибки целиком. */
    private static final int TAIL_LINES = 60;

    /**
     * Одна команда.
     *
     * @param repo название репозитория (как в repos.txt)
     * @param name название команды («тесты»)
     * @param argv программа и аргументы — уже разобранные, без оболочки
     */
    public record Command(String repo, String name, List<String> argv) {
        /** Командная строка — для показа человеку. */
        public String line() {
            return String.join(" ", argv);
        }
    }

    /**
     * Итог прогона.
     *
     * @param exitCode код завершения; -1 — не дождались
     * @param tail     последние строки вывода (stdout и stderr вместе, в порядке появления)
     */
    public record Run(Command command, int exitCode, boolean timedOut, long millis, List<String> tail) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        /** «тесты прошли (12 с)» / «тесты не прошли, код 1 (12 с)». */
        public String verdict() {
            String took = " (" + seconds(millis) + ")";
            if (ok()) return "«" + command.name() + "» прошли" + took;
            if (timedOut) return "«" + command.name() + "» не уложились в отведённое время" + took;
            return "«" + command.name() + "» не прошли, код " + exitCode + took;
        }

        /**
         * Строки, по которым видно, ЧТО сломалось: с FAIL, error, ошибкой,
         * исключением. Если таких нет — просто последние. Это для телефона:
         * 60 строк лога на экране телефона никто читать не будет.
         */
        public List<String> keyLines(int max) {
            List<String> key = tail.stream().filter(l -> KEY_LINE.matcher(l).find()).toList();
            List<String> src = key.isEmpty() ? tail : key;
            return src.subList(Math.max(0, src.size() - max), src.size());
        }
    }

    private static final Pattern KEY_LINE = Pattern.compile(
            "(?iu)fail|error|ошибк|exception|assert|expected|не прошл|failed|✗|×");

    /** Файл со списком. Свойство faust.repoCommandsFile — для тестов. */
    public static Path file() {
        String override = System.getProperty("faust.repoCommandsFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "repo-commands.txt");
    }

    /** Все команды из файла, в его порядке. Строки: «репозиторий: название = команда». */
    public static List<Command> all() {
        Path f = file();
        if (!Files.isRegularFile(f)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось прочитать " + f + ": " + e.getMessage());
            return List.of();
        }
        List<Command> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("\uFEFF")) line = line.substring(1); // метка UTF-8 от Блокнота
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            int eq = line.indexOf('=');
            // Двоеточие должно стоять до «=»: в самой команде может быть «C:\...».
            if (colon <= 0 || eq <= colon + 1) continue;
            String repo = line.substring(0, colon).strip();
            String name = line.substring(colon + 1, eq).strip();
            List<String> argv = split(line.substring(eq + 1).strip());
            if (repo.isEmpty() || name.isEmpty() || argv.isEmpty()) continue;
            out.add(new Command(repo, name, argv));
        }
        return List.copyOf(out);
    }

    /** Команды одного репозитория. */
    public static List<Command> of(RepoCatalog.Repo repo) {
        String want = AppCatalog.norm(repo.name());
        return all().stream().filter(c -> AppCatalog.norm(c.repo()).equals(want)).toList();
    }

    /**
     * Найти команду по названию: сначала точно, потом по вхождению, но только
     * если вариант один («тест» → «тесты», но «сборка» при «сборка debug» и
     * «сборка release» — нет: угадывать, какую сборку запустить, нельзя).
     */
    public static Optional<Command> find(RepoCatalog.Repo repo, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        List<Command> mine = of(repo);
        String want = AppCatalog.norm(name);
        for (Command c : mine) if (AppCatalog.norm(c.name()).equals(want)) return Optional.of(c);
        List<Command> partial = mine.stream().filter(c -> {
            String n = AppCatalog.norm(c.name());
            return !want.isEmpty() && (n.contains(want) || want.contains(n));
        }).toList();
        if (partial.size() == 1) return Optional.of(partial.get(0));
        if (!partial.isEmpty()) return Optional.empty();
        // Голосом говорят в падеже: «запусти сборкУ» при команде «сборкА».
        List<Command> stem = mine.stream().filter(c -> sameStem(AppCatalog.norm(c.name()), want)).toList();
        return stem.size() == 1 ? Optional.of(stem.get(0)) : Optional.empty();
    }

    /**
     * Одно и то же слово в разных падежах: общее начало не короче 4 букв и
     * отличаются они не больше чем последней буквой («сборка»/«сборку»,
     * «проверка»/«проверку»). Грубо, но для названий из 1–2 слов хватает,
     * а ошибиться в сторону «не нашёл» безопасно — тогда спросят планировщика.
     */
    static boolean sameStem(String a, String b) {
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) common++;
        return common >= 4 && common >= Math.min(a.length(), b.length()) - 1
               && Math.abs(a.length() - b.length()) <= 1;
    }

    /**
     * Команда проверки, которую надо прогнать перед коммитом правки: первая,
     * чьё название начинается с «тест», «test», «провер» или «check».
     */
    public static Optional<Command> testCommand(RepoCatalog.Repo repo) {
        return of(repo).stream().filter(c -> TEST_NAME.matcher(AppCatalog.norm(c.name())).find()).findFirst();
    }

    private static final Pattern TEST_NAME = Pattern.compile("^(тест|test|провер|check)");

    /** Названия команд по репозиториям — для подсказки планировщику (командных строк он не видит). */
    public static Map<String, List<String>> namesByRepo() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (RepoCatalog.Repo r : RepoCatalog.all()) {
            List<String> names = of(r).stream().map(Command::name).toList();
            if (!names.isEmpty()) out.put(r.name(), names);
        }
        return out;
    }

    /**
     * Разобрать командную строку на программу и аргументы: по пробелам, а
     * текст в двойных кавычках — одним куском («"C:\Program Files\x.exe" test»).
     */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(ch);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    /**
     * Как запустить команду: программа из папки проекта (gradlew.bat) — по
     * полному пути, иначе Windows искала бы её в папке агента, а не проекта;
     * .bat и .cmd — через cmd /c, .ps1 — через PowerShell. Всё остальное — как
     * написано, программа ищется в PATH.
     */
    static List<String> resolve(Path repoDir, List<String> argv) {
        String exe = argv.get(0);
        Path local = null;
        // Пустое расширение — последним: рядом с gradlew.bat лежит и gradlew без
        // расширения (скрипт для Linux), запустить который Windows не сможет.
        for (String ext : List.of(".bat", ".cmd", ".exe", ".ps1", "")) {
            Path p = repoDir.resolve(exe + ext);
            if (Files.isRegularFile(p)) {
                local = p.toAbsolutePath().normalize();
                break;
            }
        }
        String program = local != null ? local.toString() : exe;
        List<String> rest = argv.subList(1, argv.size());
        String low = program.toLowerCase(Locale.ROOT);
        List<String> cmd = new ArrayList<>();
        if (low.endsWith(".bat") || low.endsWith(".cmd")) {
            cmd.addAll(List.of("cmd.exe", "/c", program));
        } else if (low.endsWith(".ps1")) {
            cmd.addAll(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", program));
        } else {
            cmd.add(program);
        }
        cmd.addAll(rest);
        return cmd;
    }

    /**
     * Выполнить команду в папке репозитория и дождаться конца.
     *
     * <p>В отличие от доверенного скрипта, долгий прогон в фон НЕ уходит: тесты,
     * которые «ещё идут», ничего не доказывают, а коммит ждёт их итога. Не
     * уложилась в {@code code.commandTimeoutSec} — процесс со всеми дочерними
     * останавливается, прогон считается неудачным.
     */
    public static Run run(RepoCatalog.Repo repo, Command c) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(resolve(repo.path(), c.argv())).directory(repo.path().toFile());
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось запустить «" + c.name() + "» (" + c.line() + "): "
                                            + e.getMessage());
        }
        p.getOutputStream().close(); // ввода с клавиатуры не будет: «Press any key» не повесит агента

        Deque<String> tail = new ArrayDeque<>();
        Thread reader = new Thread(() -> {
            // Построчно, с определением кодировки у каждой строки (cp866 или UTF-8),
            // как у доверенных скриптов: Gradle пишет в UTF-8, cmd — в OEM.
            try (InputStream in = new BufferedInputStream(p.getInputStream())) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                    if (b != '\n') {
                        buf.write(b);
                        continue;
                    }
                    add(tail, ScriptCatalog.decodeLine(buf.toByteArray()));
                    buf.reset();
                }
                if (buf.size() > 0) add(tail, ScriptCatalog.decodeLine(buf.toByteArray()));
            } catch (IOException ignored) {
                // процесс закрылся — вывод кончился
            }
        }, "repo-command-output");
        reader.setDaemon(true);
        reader.start();

        int timeout = AgentSettings.number(TIMEOUT_SEC, 600, 10, 3600);
        boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            // Сначала дети (java под gradlew, node под npm), потом сам процесс:
            // иначе дети остались бы жить без родителя.
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        reader.join(2000);
        List<String> lines;
        synchronized (tail) {
            lines = List.copyOf(tail);
        }
        return new Run(c, finished ? p.exitValue() : -1, !finished, System.currentTimeMillis() - t0, lines);
    }

    private static void add(Deque<String> tail, String line) {
        String l = line.replace("\r", "").stripTrailing();
        if (l.isBlank()) return;
        synchronized (tail) {
            tail.addLast(l);
            while (tail.size() > TAIL_LINES) tail.removeFirst();
        }
    }

    static String seconds(long millis) {
        long s = Math.round(millis / 1000.0);
        return s < 60 ? s + " с" : (s / 60) + " мин " + (s % 60) + " с";
    }

    /** Создать файл-образец с пояснением, если его ещё нет. */
    public static void ensureFile() {
        Path f = file();
        if (Files.exists(f)) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, """
                    # Команды проектов, которые «Фауст» может запускать по названию: тесты, сборка, проверка.
                    # Одна строка — одна команда:   репозиторий: название = командная строка
                    # Репозиторий — название из repos.txt. Команда выполняется в папке этого проекта.
                    # ИИ видит только названия («тесты») и не может ни изменить команду, ни добавить аргументы.
                    #
                    # Команда, чьё название начинается с «тест» или «провер», прогоняется после правки
                    # кода перед коммитом. Не прошла — агент для кода один раз пробует исправить по
                    # тексту ошибки; не помогло — коммита не будет, правка останется в папке.
                    #
                    # примеры:
                    # диплом: тесты = gradlew.bat test
                    # диплом: сборка = gradlew.bat assembleDebug
                    # сайт: тесты = npm.cmd test
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось создать " + f + ": " + e.getMessage());
        }
    }
}
