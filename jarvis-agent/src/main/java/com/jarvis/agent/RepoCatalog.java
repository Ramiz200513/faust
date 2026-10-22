package com.jarvis.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Разрешённые git-репозитории — единственные папки, где агент может делать
 * commit/push и где агент для кода может править файлы.
 *
 * <h2>Почему список, а не «любая папка»</h2>
 * Та же идея, что у доверенных скриптов ({@link ScriptCatalog}): границу задаёт
 * ЧЕЛОВЕК, а не модель. Человек сам пишет в файл {@code ~/.jarvis-agent/repos.txt}
 * строки вида {@code демо = C:\Users\я\projects\demo}. Модель может только
 * назвать репозиторий из этого списка — путь она не выбирает и не видит, поэтому
 * не может ни запушить чужой проект, ни дотянуться до папки с паролями.
 *
 * <p>Отдельный файл, а не agent.properties: тот читается в кодировке ISO-8859-1,
 * и русские пути там пришлось бы писать через \u0438. Этот файл — обычный UTF-8.
 */
public final class RepoCatalog {

    private RepoCatalog() {}

    /** Один разрешённый репозиторий: как его называет человек и где он лежит. */
    public record Repo(String name, Path path) {}

    /** Файл со списком. Свойство faust.reposFile — для тестов. */
    public static Path file() {
        String override = System.getProperty("faust.reposFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "repos.txt");
    }

    /**
     * Все репозитории из файла, в его порядке. Строки «имя = путь»; пустые и
     * начинающиеся с # пропускаются. Путь, где нет папки .git, пропускается
     * тоже: агенту незачем знать о репозитории, с которым он всё равно ничего
     * не сделает, а планировщик не должен его предлагать.
     */
    public static List<Repo> all() {
        Path f = file();
        if (!Files.isRegularFile(f)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось прочитать " + f + ": " + e.getMessage());
            return List.of();
        }
        Map<String, Repo> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("\uFEFF")) line = line.substring(1); // метка UTF-8 от Блокнота
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).strip();
            String path = line.substring(eq + 1).strip().replaceAll("^[\"']|[\"']$", "");
            if (name.isEmpty() || path.isEmpty()) continue;
            Path p = Paths.get(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(p.resolve(".git"))) {
                System.err.println("repos.txt: «" + name + "» — в " + p + " нет репозитория git, пропускаю");
                continue;
            }
            out.putIfAbsent(AppCatalog.norm(name), new Repo(name, p));
        }
        return List.copyOf(out.values());
    }

    /** Названия — их видит планировщик. Путей он не видит. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Repo r : all()) out.add(r.name());
        return out;
    }

    /**
     * Найти репозиторий по названию: сначала точно, потом по вхождению
     * («диплом» -> «диплом сервер»), но только если вариант один. Пустое название
     * при единственном репозитории означает его: «запушь» без уточнений на ПК,
     * где разрешён ровно один проект, двусмысленным не бывает.
     */
    public static Optional<Repo> find(String name) {
        List<Repo> all = all();
        if (name == null || name.isBlank()) {
            return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
        }
        String want = AppCatalog.norm(name);
        for (Repo r : all) if (AppCatalog.norm(r.name()).equals(want)) return Optional.of(r);
        List<Repo> partial = all.stream().filter(r -> {
            String n = AppCatalog.norm(r.name());
            return !want.isEmpty() && (n.contains(want) || want.contains(n));
        }).toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    /** Создать файл-образец с пояснением, если его ещё нет. */
    public static void ensureFile() {
        Path f = file();
        if (Files.exists(f)) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, """
                    # Репозитории git, с которыми может работать «Фауст».
                    # Одна строка — один репозиторий:  название = путь к папке проекта
                    # Агент делает commit/push/pull и правки кода ТОЛЬКО здесь.
                    # Push и commit всегда спрашивают подтверждение на телефоне, с показом изменений.
                    #
                    # пример:
                    # диплом = C:\\Users\\я\\projects\\diploma
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось создать " + f + ": " + e.getMessage());
        }
    }
}
