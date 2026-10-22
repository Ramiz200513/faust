package com.jarvis.agent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Доверенные скрипты пользователя — единственный способ для ИИ-агента сделать
 * то, что по природе делается в терминале («подними сервер в Docker»,
 * «git pull», «npm run dev»).
 *
 * <h2>Почему это не дыра, которую мы закрыли ShellGuard'ом</h2>
 * Терминал запрещён, потому что там МОДЕЛЬ пишет команду — любую. Здесь
 * команду пишет ЧЕЛОВЕК: он кладёт .bat/.cmd/.ps1 в папку
 * {@code ~/.jarvis-agent/scripts}, и агент может только запустить такой файл
 * по имени. Изменить команду, передать аргументы или запустить файл из другой
 * папки модель не может. Для неё скрипт — кнопка, которую сделал человек.
 * И перед запуском план всё равно спрашивает подтверждение.
 */
public final class ScriptCatalog {

    private static final List<String> EXTENSIONS = List.of(".bat", ".cmd", ".ps1");
    /** Сколько ждать завершения, прежде чем считать скрипт «долгоживущим» (сервер). */
    private static final int DEFAULT_WAIT_SEC = 90;
    private static final int OUTPUT_TAIL = 12;

    private ScriptCatalog() {}

    /** Папка доверенных скриптов. Свойство faust.scriptsDir — для тестов. */
    public static Path folder() {
        String override = System.getProperty("faust.scriptsDir");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "scripts");
    }

    /** Названия доступных скриптов (имя файла без расширения) — их видит планировщик. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Path p : files()) out.add(baseName(p));
        return out;
    }

    /** Найти скрипт по названию: сначала точно, потом по вхождению («сервер» -> «сервер диплома»). */
    public static Optional<Path> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String want = AppCatalog.norm(name);
        List<Path> all = files();
        for (Path p : all) if (AppCatalog.norm(baseName(p)).equals(want)) return Optional.of(p);
        List<Path> partial = all.stream().filter(p -> AppCatalog.norm(baseName(p)).contains(want)
                || want.contains(AppCatalog.norm(baseName(p)))).toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    /** Точное название скрипта (имя файла без расширения) по тому, как его назвал человек. */
    public static Optional<String> resolve(String name) {
        return find(name).map(ScriptCatalog::baseName);
    }

    private static final String[] REQUEST_PREFIXES = {"запусти скрипт ", "выполни скрипт ", "скрипт "};

    /**
     * Просьба прямо называет скрипт: «запусти скрипт сервер диплома» ->
     * «сервер диплома». Не про скрипт — null. Те же начала фраз релей
     * отправляет агенту без разбора моделью (StubIntentParser.skillOrScript).
     */
    public static String requestedName(String goal) {
        if (goal == null) return null;
        String g = goal.strip().toLowerCase(Locale.ROOT);
        for (String prefix : REQUEST_PREFIXES) {
            if (g.startsWith(prefix)) {
                String name = goal.strip().substring(prefix.length()).strip();
                // «запусти скрипт «сервер диплома»» — кавычки и точка в конце не часть названия.
                name = name.replaceAll("^[«\"']+|[»\"'.!]+$", "").strip();
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    /**
     * Запустить скрипт по названию.
     *
     * <p>Ждём до {@code scripts.waitSec} секунд (по умолчанию 90). Скрипт
     * закончился — возвращаем код и хвост вывода; код не 0 — это ошибка шага.
     * Не закончился — значит, это что-то долгоживущее (dev-сервер), и мы
     * оставляем его работать в фоне, а шаг считаем выполненным.
     *
     * @return итог для трейса и для следующих шагов плана
     * @throws IllegalArgumentException такого скрипта нет
     * @throws IllegalStateException    скрипт завершился с ошибкой
     */
    public static String run(String name) throws IOException, InterruptedException {
        Path script = find(name).orElseThrow(() -> new IllegalArgumentException(
                "нет доверенного скрипта «" + name + "»; есть: " + String.join(", ", names())
                + " (папка " + folder() + ")"));
        ProcessBuilder pb = script.toString().toLowerCase(Locale.ROOT).endsWith(".ps1")
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                : new ProcessBuilder("cmd.exe", "/c", script.toString());
        pb.directory(script.getParent().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close(); // скрипту нечего ждать с клавиатуры: pause не повесит агента

        Deque<String> tail = new ArrayDeque<>();
        Thread reader = new Thread(() -> {
            // Читаем байты построчно: кодировку определяем у каждой строки отдельно
            // (см. decodeLine) — скрипт может переключить консоль на UTF-8 посреди работы.
            try (InputStream in = new BufferedInputStream(p.getInputStream())) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                    if (b != '\n') {
                        buf.write(b);
                        continue;
                    }
                    addLine(tail, decodeLine(buf.toByteArray()));
                    buf.reset();
                }
                if (buf.size() > 0) addLine(tail, decodeLine(buf.toByteArray()));
            } catch (IOException ignored) {
                // процесс закрылся — вывод кончился
            }
        }, "script-output");
        reader.setDaemon(true);
        reader.start();

        int waitSec = AgentSettings.number("scripts.waitSec", DEFAULT_WAIT_SEC, 5, 1800);
        boolean finished = p.waitFor(waitSec, TimeUnit.SECONDS);
        if (finished) reader.join(1000);
        String out;
        synchronized (tail) {
            out = String.join(" | ", tail);
        }
        String title = "скрипт «" + baseName(script) + "»";
        if (!finished) {
            return title + " запущен и продолжает работать в фоне (прошло " + waitSec + " с)"
                   + (out.isEmpty() ? "" : "; вывод: " + out);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(title + " завершился с кодом " + p.exitValue()
                                            + (out.isEmpty() ? "" : "; вывод: " + out));
        }
        return title + " выполнен" + (out.isEmpty() ? "" : "; вывод: " + out);
    }

    /**
     * Создать папку скриптов с пояснением, если её ещё нет — чтобы человеку
     * было куда класть свои скрипты и было понятно, как это работает.
     */
    public static void ensureFolder() {
        Path dir = folder();
        Path readme = dir.resolve("ПРОЧТИ МЕНЯ.txt");
        if (Files.exists(readme)) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(readme, """
                    Доверенные скрипты для ИИ-агента «Фауст».

                    Положите сюда .bat, .cmd или .ps1 — и агент сможет запустить его по названию
                    (имя файла без расширения), например: «Фауст, запусти скрипт сервер диплома».
                    Агент НЕ может менять эти файлы и не может запускать ничего вне этой папки.
                    Перед запуском агент спросит подтверждение.

                    Скрипт, который работает дольше 90 секунд (например, dev-сервер), агент оставит
                    работать в фоне и продолжит задачу. Время ожидания меняется строкой
                    scripts.waitSec=... в agent.properties.
                    """, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("не удалось создать папку скриптов: " + e.getMessage());
        }
    }

    private static List<Path> files() {
        Path dir = folder();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> EXTENSIONS.stream().anyMatch(ext -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void addLine(Deque<String> tail, String line) {
        synchronized (tail) {
            if (!line.isBlank()) tail.addLast(line.strip());
            while (tail.size() > OUTPUT_TAIL) tail.removeFirst();
        }
    }

    /**
     * Строка вывода скрипта -> текст. Консоль Windows по умолчанию пишет в OEM
     * (cp866 для русской), но скрипт с {@code chcp 65001} пишет в UTF-8. Если
     * байты — корректный UTF-8, это он (в cp866 русский текст почти никогда не
     * складывается в правильные UTF-8 последовательности); иначе — cp866.
     */
    static String decodeLine(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, oemCharset());
        }
    }

    private static Charset oemCharset() {
        try {
            return Charset.forName("Cp866");
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }
}
