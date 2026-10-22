package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Встроенный агент для кода: правки через Gemini на релее.
 *
 * <h2>Зачем, если есть Claude Code</h2>
 * Claude Code сильнее, но ему нужен свой вход на ПК — отдельная учётная
 * запись. Этот редактор ходит в модель тем же путём, что весь агент: через
 * релей, где лежит ключ (инвариант «ключ только на релее» соблюдён). Поэтому
 * правка кода работает сразу, без настройки. Какой редактор брать, решает
 * настройка {@code code.engine} (см. {@link CodeAgent}).
 *
 * <h2>Как устроен — и почему так надёжнее, чем «перепиши файл»</h2>
 * <ol>
 *   <li>Если проект маленький — модель сразу получает все текстовые файлы.
 *       Если большой — сначала только список файлов, и она выбирает, какие
 *       открыть (до 6). Это первый запрос.</li>
 *   <li>Модель возвращает правки в виде пар «было → стало» (search/replace), а
 *       не файлы целиком: переписывая файл, модель теряет и меняет строки,
 *       которых её не просили трогать.</li>
 *   <li>Код применяет пару, только если «было» встречается в файле РОВНО ОДИН
 *       раз. Не нашлось или нашлось дважды — правка не применяется вовсе (не
 *       угадываем место), и человек видит причину.</li>
 *   <li>Править можно только внутри папки проекта: ни «..», ни .git, ни файлы
 *       с секретами.</li>
 * </ol>
 * Цена правки — один-два запроса к модели.
 */
public final class GeminiCodeEditor {

    /** Запрос к модели (в работе — через релей, в тестах — подделка). */
    @FunctionalInterface
    public interface Llm {
        String generate(String prompt) throws Exception;
    }

    /** Проект меньше этого (в символах) уходит модели целиком, без шага выбора файлов. */
    static final int SMALL_PROJECT_CHARS = 60_000;
    /** Сколько файлов модель может открыть в большом проекте. */
    static final int MAX_FILES = 6;
    /** Файл крупнее не открываем: это не исходник, а данные или сборка. */
    static final int MAX_FILE_CHARS = 120_000;

    private static final Set<String> TEXT_EXT = Set.of(
            "java", "kt", "kts", "gradle", "py", "js", "jsx", "ts", "tsx", "json", "yml", "yaml", "xml", "html",
            "htm", "css", "scss", "md", "txt", "properties", "toml", "ini", "cfg", "conf", "sh", "bat", "cmd", "ps1",
            "sql", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift", "vue", "svelte", "env.example");
    private static final Set<String> TEXT_NAMES = Set.of(
            "dockerfile", "makefile", ".gitignore", ".dockerignore", ".editorconfig", "procfile", "readme");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Llm llm;

    public GeminiCodeEditor(Llm llm) {
        this.llm = llm;
    }

    /** Итог: какие файлы изменены и что сказала модель. */
    public record Edit(List<String> changed, String summary) {}

    public Edit edit(RepoCatalog.Repo repo, String task) throws Exception {
        List<String> files = textFiles(repo.path());
        if (files.isEmpty()) throw new IllegalStateException("в проекте нет текстовых файлов, которые можно править");

        Map<String, String> contents = new LinkedHashMap<>();
        long total = 0;
        for (String f : files) total += size(repo.path().resolve(f));
        if (total <= SMALL_PROJECT_CHARS) {
            for (String f : files) contents.put(f, read(repo.path().resolve(f)));
        } else {
            for (String f : pickFiles(task, files)) {
                if (files.contains(f)) contents.put(f, read(repo.path().resolve(f)));
            }
        }

        String reply = llm.generate(editPrompt(repo.name(), task, files, contents));
        JsonNode n = json(reply);
        if (n.hasNonNull("refuse") && !n.get("refuse").asText().isBlank()) {
            throw new IllegalStateException("редактор отказался: " + n.get("refuse").asText().strip());
        }

        // Сначала проверяем ВСЕ правки, потом пишем: если одна не подходит,
        // проект не должен остаться наполовину изменённым.
        Map<Path, String> toWrite = new LinkedHashMap<>();
        for (JsonNode e : n.path("edits")) {
            String file = e.path("file").asText("").strip();
            Path p = safePath(repo.path(), file);
            String original = toWrite.containsKey(p) ? toWrite.get(p) : Files.exists(p) ? read(p) : null;
            if (original == null) throw new IllegalStateException("правка в несуществующем файле «" + file + "»");
            toWrite.put(p, applyOne(original, e.path("search").asText(""), e.path("replace").asText(""), file));
        }
        for (JsonNode c : n.path("create")) {
            String file = c.path("file").asText("").strip();
            Path p = safePath(repo.path(), file);
            if (Files.exists(p) || toWrite.containsKey(p)) {
                throw new IllegalStateException("файл «" + file + "» уже есть — его нужно править, а не создавать");
            }
            toWrite.put(p, c.path("content").asText(""));
        }
        if (toWrite.isEmpty()) {
            throw new IllegalStateException("редактор не предложил ни одной правки"
                                            + (n.hasNonNull("summary") ? ": " + n.get("summary").asText() : ""));
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<Path, String> w : toWrite.entrySet()) {
            write(w.getKey(), w.getValue());
            changed.add(repo.path().relativize(w.getKey()).toString().replace('\\', '/'));
        }
        return new Edit(changed, n.path("summary").asText("").strip());
    }

    /** Большой проект: модель выбирает, какие файлы открыть. */
    private List<String> pickFiles(String task, List<String> files) throws Exception {
        String prompt = "Ты помогаешь править код. Задача: " + task.strip() + "\n\n"
                        + "Файлы проекта:\n" + String.join("\n", files.subList(0, Math.min(400, files.size())))
                        + "\n\nКакие файлы нужно открыть, чтобы выполнить задачу (не больше " + MAX_FILES + ")?\n"
                        + "Ответь строго JSON без markdown: {\"files\":[\"путь\", ...]}";
        List<String> out = new ArrayList<>();
        for (JsonNode f : json(llm.generate(prompt)).path("files")) {
            if (out.size() < MAX_FILES) out.add(f.asText().strip().replace('\\', '/'));
        }
        if (out.isEmpty()) throw new IllegalStateException("редактор не нашёл, какие файлы относятся к задаче");
        return out;
    }

    static String editPrompt(String repoName, String task, List<String> files, Map<String, String> contents) {
        StringBuilder sb = new StringBuilder()
                .append("Ты — аккуратный редактор кода в проекте «").append(repoName).append("».\n")
                .append("ЗАДАЧА (пришла голосом с телефона): ").append(task.strip()).append("\n\n")
                .append("Все файлы проекта:\n").append(String.join("\n", files)).append("\n\n")
                .append("Содержимое нужных файлов:\n");
        contents.forEach((f, c) -> sb.append("===== ").append(f).append(" =====\n").append(c)
                .append(c.endsWith("\n") ? "" : "\n"));
        sb.append("""

                Правила:
                - меняй только то, что нужно для задачи, как можно меньше;
                - правка = пара: "search" — ТОЧНЫЙ фрагмент из файла (несколько строк целиком, с теми же
                  отступами), который встречается в файле ровно один раз; "replace" — чем его заменить;
                - новый файл — в "create" с полным содержимым;
                - не трогай файлы с секретами (.env, ключи) и не добавляй в код пароли и ключи;
                - если задача неясна, опасна или не про код — ничего не меняй, верни "refuse" с причиной.

                Ответь СТРОГО одним JSON без markdown:
                {"edits":[{"file":"путь","search":"...","replace":"..."}],
                 "create":[{"file":"путь","content":"..."}],
                 "summary":"одна короткая фраза по-русски: что изменено (её же возьмём сообщением коммита)"}
                или {"refuse":"причина"}
                """);
        return sb.toString();
    }

    /**
     * Применить одну пару «было → стало». Концы строк сравниваются без учёта
     * \r: на Windows git отдаёт файлы с CRLF, а модель пишет \n. Записывается
     * файл в своём исходном стиле концов строк.
     */
    static String applyOne(String original, String search, String replace, String file) {
        if (search.isEmpty()) throw new IllegalStateException("пустой фрагмент «было» в правке файла «" + file + "»");
        boolean crlf = original.contains("\r\n");
        String text = original.replace("\r\n", "\n");
        String s = search.replace("\r\n", "\n");
        int first = text.indexOf(s);
        if (first < 0) {
            throw new IllegalStateException("в файле «" + file + "» не найден фрагмент, который нужно заменить");
        }
        if (text.indexOf(s, first + 1) >= 0) {
            throw new IllegalStateException("в файле «" + file + "» фрагмент встречается несколько раз — "
                                            + "не угадываю, какой менять");
        }
        String result = text.substring(0, first) + replace.replace("\r\n", "\n") + text.substring(first + s.length());
        return crlf ? result.replace("\n", "\r\n") : result;
    }

    /** Путь внутри проекта, не в .git и не секрет — иначе отказ. */
    static Path safePath(Path root, String file) {
        if (file == null || file.isBlank()) throw new IllegalStateException("правка без имени файла");
        Path p = root.resolve(file.replace('\\', '/')).normalize();
        if (!p.startsWith(root.normalize())) throw new IllegalStateException("файл «" + file + "» вне проекта");
        String rel = root.normalize().relativize(p).toString().replace('\\', '/');
        if (rel.equals(".git") || rel.startsWith(".git/")) throw new IllegalStateException("в папку .git писать нельзя");
        if (!GitTool.secretFiles(List.of(rel)).isEmpty()) {
            throw new IllegalStateException("файл «" + file + "» похож на файл с секретами — не трогаю");
        }
        return p;
    }

    /** Текстовые файлы проекта по git (отслеживаемые и новые, без игнорируемых), без секретов. */
    static List<String> textFiles(Path repo) throws IOException {
        GitTool.Out out = GitTool.git(repo, Duration.ofSeconds(20), null, "ls-files", "-co", "--exclude-standard");
        List<String> files = new ArrayList<>();
        for (String line : out.text().split("\\R")) {
            String f = line.strip();
            if (f.isEmpty() || !isText(f) || !GitTool.secretFiles(List.of(f)).isEmpty()) continue;
            Path p = repo.resolve(f);
            if (Files.isRegularFile(p) && size(p) <= MAX_FILE_CHARS) files.add(f);
        }
        return files;
    }

    private static boolean isText(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (TEXT_NAMES.contains(name) || name.startsWith("dockerfile") || name.startsWith("docker-compose")) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXT.contains(name.substring(dot + 1));
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static void write(Path p, String content) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    /** JSON из ответа модели: без ```json и текста вокруг. */
    static JsonNode json(String reply) throws IOException {
        String t = reply == null ? "" : reply.strip();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("редактор ответил не JSON: " + t.substring(0, Math.min(150, t.length())));
        return MAPPER.readTree(t.substring(a, b + 1));
    }
}
