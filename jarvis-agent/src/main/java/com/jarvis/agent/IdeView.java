package com.jarvis.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Показать правку агента для кода в среде разработки: открыть изменённый файл
 * на изменённой строке в Android Studio, IntelliJ IDEA, PyCharm или VS Code.
 *
 * <h2>Почему так, а не «управлять ИИ-помощником IDE через окно»</h2>
 * Замер 11.11: UI Automation видит в окне Android Studio 0 элементов. Среды
 * JetBrains рисуют интерфейс сами (Swing) и наружу его отдают только через
 * Java Access Bridge — другой механизм, который агент не читает. Остаётся
 * управление по снимкам экрана, а это медленно, платно за каждый шаг и
 * хрупко. Зато у всех этих сред есть официальная команда запуска «открой
 * файл на строке N» — её выполняет КОД, без модели и без кликов:
 * <pre>
 *   studio64.exe --line 12 C:\proj\app\build.gradle.kts
 *   Code.exe -g C:\proj\index.html:12
 * </pre>
 * Если среда уже запущена, команда передаётся ей, и файл открывается в
 * существующем окне.
 *
 * <h2>Когда открывать</h2>
 * Настройка {@code code.showInIde}: {@code auto} (по умолчанию) — только если
 * подходящая среда уже открыта (агент не запускает тяжёлую программу без
 * спроса); {@code always} — запустить, даже если закрыта; {@code off} — никогда.
 */
public final class IdeView {

    private IdeView() {}

    public static final String SHOW = "code.showInIde";

    /** Сколько файлов открывать за одну правку: больше — вкладки только мешают. */
    private static final int MAX_FILES = 3;

    /** Среда разработки: как называть человеку, имя программы и как передать файл со строкой. */
    enum Ide {
        ANDROID_STUDIO("Android Studio", "studio64.exe", true),
        INTELLIJ("IntelliJ IDEA", "idea64.exe", true),
        PYCHARM("PyCharm", "pycharm64.exe", true),
        VS_CODE("VS Code", "Code.exe", false);

        final String title;
        final String exe;
        final boolean jetbrains;

        Ide(String title, String exe, boolean jetbrains) {
            this.title = title;
            this.exe = exe;
            this.jetbrains = jetbrains;
        }

        /** Аргументы «открой файл на строке» — у JetBrains и у VS Code они разные. */
        List<String> openArgs(String program, Path file, int line) {
            return jetbrains
                    ? List.of(program, "--line", String.valueOf(line), file.toString())
                    : List.of(program, "-g", file + ":" + line);
        }
    }

    /** Какой проект перед нами — от этого зависит, какая среда ему «родная». */
    enum ProjectKind { ANDROID, PYTHON, JVM, OTHER }

    /**
     * Открыть изменённые файлы в среде разработки.
     *
     * @return что сделано («открыто в Android Studio: build.gradle.kts:12») или null — не открывали
     */
    public static String show(RepoCatalog.Repo repo, List<String> changed) {
        String mode = AgentSettings.text(SHOW, "auto").toLowerCase(Locale.ROOT);
        if (mode.equals("off") || changed == null || changed.isEmpty()) return null;
        try {
            Map<Ide, String> running = running();
            Optional<Ide> ide = choose(kind(repo.path()), running.keySet());
            String program;
            if (ide.isPresent()) {
                program = running.get(ide.get());
            } else if (mode.equals("always")) {
                Map<Ide, String> installed = installed();
                ide = choose(kind(repo.path()), installed.keySet());
                if (ide.isEmpty()) return null;
                program = installed.get(ide.get());
            } else {
                return null; // auto и ни одна подходящая среда не открыта — не навязываемся
            }
            List<String> shown = new ArrayList<>();
            List<String> files = changed.stream()
                    .filter(f -> Files.isRegularFile(repo.path().resolve(f)))
                    .limit(MAX_FILES)
                    .toList();
            // В обратном порядке: каждая команда делает свой файл активным, а
            // активным в конце должен остаться первый — главный — файл правки.
            for (int i = files.size() - 1; i >= 0; i--) {
                String f = files.get(i);
                int line = firstChangedLine(repo, f);
                new ProcessBuilder(ide.get().openArgs(program, repo.path().resolve(f).toAbsolutePath().normalize(), line))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                shown.add(0, f + ":" + line);
            }
            return shown.isEmpty() ? null : "открыто в " + ide.get().title + ": " + String.join(", ", shown);
        } catch (Exception e) {
            // Показ — украшение, а не часть правки: неудача не должна ронять шаг.
            System.err.println("не удалось открыть правку в среде разработки: " + e.getMessage());
            return null;
        }
    }

    /**
     * Выбрать среду: «родная» для проекта, если она есть среди доступных;
     * иначе любая доступная JetBrains (они открывают любой текст), иначе VS Code.
     */
    static Optional<Ide> choose(ProjectKind kind, java.util.Collection<Ide> available) {
        if (available.isEmpty()) return Optional.empty();
        List<Ide> order = switch (kind) {
            case ANDROID -> List.of(Ide.ANDROID_STUDIO, Ide.INTELLIJ, Ide.VS_CODE, Ide.PYCHARM);
            case PYTHON -> List.of(Ide.PYCHARM, Ide.VS_CODE, Ide.INTELLIJ, Ide.ANDROID_STUDIO);
            case JVM -> List.of(Ide.INTELLIJ, Ide.ANDROID_STUDIO, Ide.VS_CODE, Ide.PYCHARM);
            case OTHER -> List.of(Ide.VS_CODE, Ide.INTELLIJ, Ide.PYCHARM, Ide.ANDROID_STUDIO);
        };
        return order.stream().filter(available::contains).findFirst();
    }

    /** Тип проекта по файлам в его корне — без чтения всего дерева. */
    static ProjectKind kind(Path dir) {
        for (String g : List.of("build.gradle.kts", "build.gradle", "app/build.gradle.kts", "app/build.gradle")) {
            Path f = dir.resolve(g);
            try {
                if (Files.isRegularFile(f) && Files.readString(f).contains("com.android")) return ProjectKind.ANDROID;
            } catch (IOException ignored) {
                // нечитаемый файл сборки — смотрим дальше
            }
        }
        if (Files.exists(dir.resolve("pyproject.toml")) || Files.exists(dir.resolve("requirements.txt"))
            || Files.exists(dir.resolve("setup.py"))) return ProjectKind.PYTHON;
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))
            || Files.exists(dir.resolve("settings.gradle.kts")) || Files.exists(dir.resolve("pom.xml"))) {
            return ProjectKind.JVM;
        }
        return ProjectKind.OTHER;
    }

    /** Запущенные среды: среда -> полный путь к её программе (из самого процесса). */
    static Map<Ide, String> running() {
        Map<Ide, String> out = new LinkedHashMap<>();
        ProcessHandle.allProcesses().forEach(ph -> ph.info().command().ifPresent(cmd -> {
            String exe = Paths.get(cmd).getFileName().toString();
            for (Ide ide : Ide.values()) if (ide.exe.equalsIgnoreCase(exe)) out.putIfAbsent(ide, cmd);
        }));
        return out;
    }

    /** Установленные среды — по обычным местам установки (для режима always). */
    static Map<Ide, String> installed() {
        Map<Ide, String> out = new LinkedHashMap<>();
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
        putIfFile(out, Ide.ANDROID_STUDIO, Paths.get(pf, "Android", "Android Studio", "bin", "studio64.exe"));
        putIfFile(out, Ide.VS_CODE, Paths.get(local, "Programs", "Microsoft VS Code", "Code.exe"));
        putIfFile(out, Ide.VS_CODE, Paths.get(pf, "Microsoft VS Code", "Code.exe"));
        Path jb = Paths.get(pf, "JetBrains");
        if (Files.isDirectory(jb)) {
            try (Stream<Path> dirs = Files.list(jb)) {
                for (Path d : dirs.sorted(java.util.Comparator.reverseOrder()).toList()) { // новая версия — первой
                    putIfFile(out, Ide.INTELLIJ, d.resolve("bin").resolve("idea64.exe"));
                    putIfFile(out, Ide.PYCHARM, d.resolve("bin").resolve("pycharm64.exe"));
                }
            } catch (IOException ignored) {
                // нет доступа к папке — значит, JetBrains не предложим
            }
        }
        return out;
    }

    private static void putIfFile(Map<Ide, String> out, Ide ide, Path p) {
        if (!out.containsKey(ide) && Files.isRegularFile(p)) out.put(ide, p.toString());
    }

    /** Первая изменённая строка файла по git diff; новый файл или не удалось понять — 1. */
    static int firstChangedLine(RepoCatalog.Repo repo, String file) {
        try {
            GitTool.Out d = GitTool.git(repo.path(), Duration.ofSeconds(10), null, "diff", "-U0", "--", file);
            return d.ok() ? firstChangedLine(d.text()) : 1;
        } catch (IOException e) {
            return 1;
        }
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@", Pattern.MULTILINE);

    /**
     * Из текста {@code git diff -U0}: строка, с которой начинается первый кусок
     * правки в НОВОЙ версии файла. Кусок только из удалений («+12,0») указывает
     * на строку ПЕРЕД удалённым — показываем следующую за ней.
     */
    static int firstChangedLine(String diff) {
        Matcher m = HUNK.matcher(diff == null ? "" : diff);
        if (!m.find()) return 1;
        int start = Integer.parseInt(m.group(1));
        boolean onlyRemoved = "0".equals(m.group(2));
        return Math.max(1, onlyRemoved ? start + 1 : start);
    }
}
