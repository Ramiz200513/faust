package com.jarvis.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * Голос самого ПК — для режима наставника: «Нажмите «Специальные возможности»» звучит из
 * колонок компьютера, а не только из телефона, который может лежать в другой комнате.
 *
 * <h2>Два голоса</h2>
 * <ol>
 *   <li><b>Silero</b> (с 23.11, по умолчанию) — нейросеть на этом ПК, мужской голос «eugene»:
 *       звучит заметно человечнее встроенных голосов Windows. Это скрипт
 *       {@code jarvis-agent/scripts/silero_voice.py} на Python с PyTorch
 *       ({@code jarvis-ml/.venv}) и модель {@code jarvis-agent/tools/silero/v4_ru.pt}.
 *       Без интернета: подсказки никуда не отправляются.</li>
 *   <li><b>System.Speech</b> — встроенный синтез Windows (тот же, что у экранного диктора),
 *       русская «Microsoft Irina». Запасной путь: на другом ПК (например, из установщика)
 *       Python с PyTorch и модели нет — тогда говорит он.</li>
 * </ol>
 * Выбор — {@code mentor.voice.engine} в agent.properties: {@code auto} (Silero, если есть, иначе
 * Windows), {@code silero}, {@code system}. Голос Silero — {@code mentor.voice.speaker}
 * (eugene, aidar, baya, kseniya, xenia). Выключить голос совсем — {@code mentor.voice=false}.
 *
 * <h2>Как</h2>
 * Запускать процесс на каждую фразу нельзя (PowerShell — около секунды, Silero — ещё и
 * загрузка модели), поэтому работает ОДИН фоновый процесс, читающий фразы построчно со
 * стандартного ввода. Новая фраза прерывает старую: человек уже сделал шаг — дочитывать
 * прошлую подсказку незачем.
 *
 * <p>Процесс Silero общий на всё время работы агента: первые фразы после загрузки модели
 * считаются по 2–9 с («прогрев» PyTorch, замер 23.11), дальше — ~0,15 с. Поэтому агент
 * запускает его при старте ({@link #preloadInBackground}), а урок в конце только обрывает
 * речь, но не выключает процесс. Процесс System.Speech, как и раньше, живёт один урок.
 */
public final class PcVoice implements AutoCloseable {

    public static final String ENABLED = "mentor.voice";
    public static final String ENGINE = "mentor.voice.engine";
    public static final String SPEAKER = "mentor.voice.speaker";

    // Скрипт передаётся через -EncodedCommand (UTF-16LE в base64): так кавычки и кириллица в
    // нём не ломаются при передаче через командную строку.
    private static final String SYSTEM_SCRIPT = """
            [Console]::InputEncoding = [Text.Encoding]::UTF8
            $ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Speech
            $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $ru = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'ru*' } | Select-Object -First 1
            if ($ru) { $s.SelectVoice($ru.VoiceInfo.Name); [Console]::Out.WriteLine('VOICE ' + $ru.VoiceInfo.Name) }
            else { [Console]::Out.WriteLine('NOVOICE') }
            [Console]::Out.Flush()
            while ($true) {
              $line = [Console]::In.ReadLine()
              if ($line -eq $null) { break }
              $s.SpeakAsyncCancelAll()
              if ($line.Length -gt 0) { $s.SpeakAsync($line) | Out-Null }
            }
            $s.Dispose()
            """;

    /** Общий процесс Silero (null — ещё не запускали или он недоступен). */
    private static PcVoice silero;
    /** Пробовали запустить Silero и не вышло — второй раз за работу агента не пробуем. */
    private static boolean sileroFailed;

    private final List<String> command;
    private final String name;
    /** Общий процесс: close() урока только обрывает речь, процесс живёт дальше. */
    private final boolean shared;
    private Process process;
    private Writer in;
    private volatile boolean ready;

    private PcVoice(List<String> command, String name, boolean shared) {
        this.command = command;
        this.name = name;
        this.shared = shared;
    }

    /** Голос, если он включён: Silero, иначе Windows; null — голоса нет (наставник молчит). */
    public static PcVoice openIfEnabled() {
        if (!AgentSettings.flag(ENABLED, true)) return null;
        String engine = AgentSettings.text(ENGINE, "auto");
        if (!"system".equalsIgnoreCase(engine)) {
            PcVoice s = sileroShared();
            if (s != null) return s;
        }
        PcVoice v = systemVoice();
        return v.start() ? v : null;
    }

    /**
     * Запустить Silero заранее, в фоне, — при старте агента: к первому уроку модель уже
     * загружена и прогрета. Ничего не делает, если голос выключен или выбран голос Windows.
     */
    public static void preloadInBackground() {
        if (!AgentSettings.flag(ENABLED, true)) return;
        if ("system".equalsIgnoreCase(AgentSettings.text(ENGINE, "auto"))) return;
        Thread t = new Thread(PcVoice::sileroShared, "pc-voice-preload");
        t.setDaemon(true);
        t.start();
    }

    /** Общий процесс Silero: запустить при первом обращении; null — Silero здесь недоступен. */
    static synchronized PcVoice sileroShared() {
        if (silero != null && silero.ready && silero.process != null && silero.process.isAlive()) return silero;
        if (silero != null) {                 // процесс умер (например, убили вручную) — один перезапуск
            silero = null;
        } else if (sileroFailed) {
            return null;
        }
        List<String> cmd = sileroCommand(projectRoot(), AgentSettings.text(SPEAKER, "eugene"));
        if (cmd == null) {
            System.err.println("голос ПК: Silero не найден (нужны jarvis-ml/.venv, scripts/silero_voice.py, "
                               + "tools/silero/v4_ru.pt) — будет голос Windows");
            sileroFailed = true;
            return null;
        }
        PcVoice v = new PcVoice(cmd, "Silero", true);
        if (v.start()) {
            silero = v;
            return v;
        }
        sileroFailed = true;
        return null;
    }

    /**
     * Команда запуска Silero или null, если чего-то не хватает. Пути — от корня проекта:
     * {@code python -I scripts/silero_voice.py tools/silero/v4_ru.pt eugene}. «-I» — изолированный
     * режим Python: он не подхватит чужие модули из текущей папки и переменных окружения.
     */
    static List<String> sileroCommand(Path root, String speaker) {
        if (root == null) return null;
        Path python = root.resolve("jarvis-ml/.venv/Scripts/python.exe");
        Path script = root.resolve("jarvis-agent/scripts/silero_voice.py");
        Path model = root.resolve("jarvis-agent/tools/silero/v4_ru.pt");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(script) || !Files.isRegularFile(model)) return null;
        String who = speaker == null || !speaker.matches("[a-z]{2,20}") ? "eugene" : speaker;
        return List.of(python.toString(), "-I", script.toString(), model.toString(), who);
    }

    /**
     * Корень проекта (папка, где лежат jarvis-agent и jarvis-ml): поднимаемся от места, откуда
     * запущен агент (build/install/jarvis-agent/lib/*.jar или build/classes), не выше 7 уровней.
     * Установленная программа лежит в %LOCALAPPDATA%\Faust — там корня нет, и это нормально.
     */
    static Path projectRoot() {
        try {
            Path p = Paths.get(PcVoice.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (int i = 0; i < 7 && p != null; i++, p = p.getParent()) {
                if (Files.isDirectory(p.resolve("jarvis-agent")) && Files.isDirectory(p.resolve("jarvis-ml"))) return p;
            }
        } catch (Exception ignored) {
            // нет пути к классам (необычный запуск) — просто без Silero
        }
        return null;
    }

    private static PcVoice systemVoice() {
        String encoded = Base64.getEncoder().encodeToString(SYSTEM_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        return new PcVoice(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded),
                "Windows", false);
    }

    private boolean start() {
        try {
            // stderr отбрасываем: замер 19.11 показал, что PowerShell при перенаправленном выводе пишет
            // туда прогресс Add-Type в виде «#< CLIXML …». Когда stderr был слит со stdout, эта строка
            // приходила ПЕРВОЙ — и голос считался отсутствующим, хотя Irina установлена. Python туда
            // же пишет предупреждения PyTorch.
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String first = readVoiceLine(out);                    // VOICE <имя> | NOVOICE | null
            // Дальше скрипт ничего не должен печатать, но если напечатает — непрочитанный вывод
            // заполнил бы канал (~64 КБ), процесс встал бы на записи и речь тихо оборвалась.
            // Фоновый поток просто вычитывает и выбрасывает всё, что придёт.
            // У Silero здесь же приходят ответы на «#FILE» (голос для телефона) — их раздаём ждущим.
            Thread drain = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) onScriptLine(line);
                } catch (IOException ignored) {
                    // процесс закрыт — читать больше нечего
                } finally {
                    // Процесс умер — никто из ждущих ответа уже не дождётся.
                    pendingWavs.values().forEach(f -> f.complete(null));
                    pendingWavs.clear();
                }
            }, "pc-voice-drain");
            drain.setDaemon(true);
            drain.start();
            if (first == null || !first.startsWith("VOICE ")) {
                System.err.println("голос ПК (" + name + "): голоса нет"
                                   + (shared ? " — будет голос Windows" : " — подсказки только на экране и на телефоне"));
                stopProcess();
                return false;
            }
            System.err.println("голос ПК: " + first.substring(6));
            ready = true;
            return true;
        } catch (IOException e) {
            System.err.println("голос ПК (" + name + ") недоступен: " + e.getMessage());
            stopProcess();
            return false;
        }
    }

    /**
     * Ищет ответ скрипта о голосе среди первых строк вывода: перед ним может оказаться служебный
     * текст PowerShell (профиль, предупреждения). Не нашли за 20 строк — считаем, что голоса нет.
     */
    static String readVoiceLine(BufferedReader out) throws IOException {
        for (int i = 0; i < 20; i++) {
            String line = out.readLine();
            if (line == null) return null;
            if (line.startsWith("VOICE ") || line.equals("NOVOICE")) return line;
        }
        return null;
    }

    /** Запросы «#FILE» к Silero, ждущие ответа: номер -> путь к WAV (null — не вышло). */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>> pendingWavs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong wavSeq = new java.util.concurrent.atomic.AtomicLong();

    /** Строка от скрипта: «WAV <id> <путь>» / «WAVERR <id> <причина>» — ответ на запрос телефона. */
    void onScriptLine(String line) {
        String[] p = line.split(" ", 3);
        if (p.length < 2 || !(p[0].equals("WAV") || p[0].equals("WAVERR"))) return;   // прочий вывод — мимо
        java.util.concurrent.CompletableFuture<String> f = pendingWavs.remove(p[1]);
        if (f != null) f.complete(p[0].equals("WAV") && p.length == 3 ? p[2] : null);
    }

    /**
     * Голос «Фауста» для ТЕЛЕФОНА: та же фраза тем же голосом Silero, но не вслух на ПК, а
     * звуковым файлом (WAV, 24 кГц, моно) — телефон проиграет его сам. Null — голоса Silero
     * нет, фраза пустая или не успели за {@code timeoutMs}: тогда телефон говорит своим голосом.
     */
    public static byte[] wavForPhone(String text, long timeoutMs) {
        if (!AgentSettings.flag(ENABLED, true) || "system".equalsIgnoreCase(AgentSettings.text(ENGINE, "auto"))) return null;
        PcVoice s = sileroShared();
        return s == null ? null : s.synthesize(text, timeoutMs);
    }

    private byte[] synthesize(String text, long timeoutMs) {
        if (!ready || text == null || text.isBlank()) return null;
        String id = Long.toString(wavSeq.incrementAndGet());
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
        pendingWavs.put(id, f);
        synchronized (this) {
            write("#FILE " + id + " " + text.replaceAll("[\\r\\n]+", " ").strip());
        }
        try {
            String path = f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return path == null ? null : Files.readAllBytes(Paths.get(path));
        } catch (Exception e) {
            return null;                      // не успели или файл не прочитался — телефон скажет сам
        } finally {
            pendingWavs.remove(id);
        }
    }

    /** Сказать фразу (прерывает предыдущую). Из любого потока, не ждёт конца речи. */
    public synchronized void say(String text) {
        if (!ready || text == null) return;
        write(text.replaceAll("[\\r\\n]+", " ").strip());   // перевод строки оборвал бы фразу
    }

    private void write(String line) {
        try {
            in.write(line + "\n");
            in.flush();
        } catch (IOException e) {
            ready = false;   // процесс закрылся — дальше молча
        }
    }

    /**
     * Урок закончился. Общий процесс Silero — только замолчать (пустая строка), он нужен
     * следующему уроку уже прогретым. Процесс Windows — закрыть.
     */
    @Override
    public synchronized void close() {
        if (shared) {
            if (ready) write("");
            return;
        }
        stopProcess();
    }

    /** Закрытый ввод — сигнал скрипту завершиться; не завершился за 2 с — снимаем. */
    private synchronized void stopProcess() {
        ready = false;
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
        if (process != null) {
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
