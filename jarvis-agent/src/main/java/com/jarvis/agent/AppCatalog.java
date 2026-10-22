package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Что разрешено открывать командой open_app. Граница безопасности: LLM даёт
 * только ИМЯ, а запустить мы можем лишь то, что уже есть в каталоге — как
 * CommandWhitelist на релее, только про программы.
 *
 * <p>Каталог состоит из двух частей:
 * <ol>
 *   <li>зашитые псевдонимы ниже — блокнот, калькулятор, браузер, папки
 *       пользователя. Они имеют ПРИОРИТЕТ: «блокнот» всегда откроет
 *       notepad.exe, а не случайное приложение с похожим названием;</li>
 *   <li>всё, что видно в меню «Пуск» — подтягивается динамически через
 *       {@code Get-StartApps} (см. {@link #installedAppsCache()}).</li>
 * </ol>
 *
 * <p><b>Почему это не размывает модель безопасности.</b> Множество остаётся
 * ЗАКРЫТЫМ — просто теперь это не «пять программ, которые я вписал руками», а
 * «то, что уже установлено на этом ПК и видно в Пуск». Ровно то же самое
 * пользователь открывает сам через Win+S. Произвольный путь с диска LLM
 * по-прежнему назвать не может: имя обязано совпасть с записью каталога.
 *
 * <p><b>Почему не список путей в коде.</b> Дописать сюда Telegram означало бы
 * через месяц дописывать Discord, потом VS Code, и так без конца. Источник
 * истины о том, «что установлено», у Windows уже есть — им и пользуемся.
 */
public final class AppCatalog {

    /** Что в итоге нужно сделать с найденной записью — решает CommandExecutor. */
    public sealed interface Target {
        record Program(String executable) implements Target {}
        record Folder(Path path) implements Target {}
        record DefaultBrowser() implements Target {}
        /** Найдено динамически в меню «Пуск» — и обычная программа, и магазинное приложение. */
        record StartMenuApp(String appId, String displayName) implements Target {}
    }

    private static final Map<String, Target> BY_ALIAS = new LinkedHashMap<>();

    private static void program(String executable, String... aliases) {
        for (String a : aliases) BY_ALIAS.put(a, new Target.Program(executable));
    }

    private static void folder(String folderName, String... aliases) {
        Path path = Paths.get(System.getProperty("user.home"), folderName);
        for (String a : aliases) BY_ALIAS.put(a, new Target.Folder(path));
    }

    static {
        program("notepad.exe", "блокнот", "notepad");
        program("calc.exe", "калькулятор", "calc", "calculator");
        program("explorer.exe", "проводник", "explorer");
        program("mspaint.exe", "paint", "пэйнт", "мспэйнт", "mspaint");
        program("taskmgr.exe", "диспетчер задач", "диспетчер", "taskmgr", "task manager");

        for (String a : new String[]{"браузер", "browser", "chrome", "edge"}) {
            BY_ALIAS.put(a, new Target.DefaultBrowser());
        }

        folder("Downloads", "загрузки", "downloads");
        folder("Documents", "документы", "documents");
        folder("Desktop", "рабочий стол", "desktop");
        folder("Pictures", "изображения", "pictures");
    }

    /**
     * Кэш «всё из меню Пуск», ключ — нормализованное имя. Строится ОДИН РАЗ за
     * жизнь агента: лениво при первом промахе по зашитым псевдонимам либо
     * заранее через {@link #warmUp()}.
     *
     * <p>null — ещё не строился. Пустая карта — строился, но ничего не нашёл
     * или PowerShell недоступен; повторять не будем, иначе один сломанный
     * PowerShell тормозил бы КАЖДУЮ нераспознанную фразу на несколько секунд.
     */
    private static volatile Map<String, Target.StartMenuApp> installedApps;

    private AppCatalog() {}

    // --- динамический каталог ------------------------------------------

    private static synchronized Map<String, Target.StartMenuApp> installedAppsCache() {
        if (installedApps != null) return installedApps;

        Map<String, Target.StartMenuApp> found = new LinkedHashMap<>();
        try {
            // [Console]::OutputEncoding обязателен: имена приложений бывают
            // кириллическими, а в перенаправленный поток PowerShell по
            // умолчанию пишет в кодировке консоли (cp866) — без этой строки
            // «Калькулятор» приезжает кракозябрами.
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                            + "Get-StartApps | ConvertTo-Json -Compress"
            ).redirectErrorStream(false).start();
            process.getOutputStream().close();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            } else if (!sb.isEmpty()) {
                JsonNode root = new ObjectMapper().readTree(sb.toString());
                // С ровно одним результатом Get-StartApps отдаёт объект, а не массив.
                Iterable<JsonNode> items = root.isArray() ? root : List.of(root);
                for (JsonNode item : items) {
                    String name = item.path("Name").asText("");
                    String appId = item.path("AppID").asText("");
                    if (name.isBlank() || appId.isBlank()) continue;
                    // putIfAbsent: при одинаковых именах побеждает первое из
                    // Get-StartApps — порядок там тот же, что в самом «Пуске».
                    found.putIfAbsent(norm(name), new Target.StartMenuApp(appId, name));
                }
            }
            System.out.println("AppCatalog: из меню «Пуск» прочитано приложений: " + found.size());
        } catch (Exception e) {
            // PowerShell недоступен или заблокирован политикой — не роняем
            // агента: зашитые псевдонимы продолжают работать как раньше.
            System.err.println("AppCatalog: Get-StartApps недоступен: " + e.getMessage());
        }

        installedApps = found;
        return found;
    }

    /**
     * Прогреть каталог заранее (см. AgentMain) — чтобы самая первая команда
     * «открой телеграм» за сессию не ждала PowerShell. Сама ловит все
     * исключения, поэтому вызывать можно прямо из фонового потока.
     */
    public static void warmUp() {
        installedAppsCache();
    }

    /**
     * Названия программ из «Пуска» — для планировщика многоэтапных задач.
     * Без удалятелей, справок и ссылок на сайты: в «Пуске» их десятки, а
     * планировщику они только засоряют список (и удлиняют запрос).
     */
    public static List<String> installedAppNames() {
        List<String> names = new ArrayList<>();
        for (Target.StartMenuApp app : installedAppsCache().values()) {
            String n = app.displayName();
            String low = n.toLowerCase(Locale.ROOT);
            if (low.contains("uninstall") || low.contains("удален") || low.contains("удалить")
                    || low.contains("readme") || low.contains("справк") || low.contains("help")
                    || low.contains("documentation") || low.contains("website") || low.contains("release notes")) {
                continue;
            }
            names.add(n);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Перечитать меню «Пуск» — после установки новой программы при запущенном агенте. */
    public static synchronized void refresh() {
        installedApps = null;
        installedAppsCache();
    }

    // --- поиск ----------------------------------------------------------

    /**
     * Сопоставление идёт от точного к приблизительному и останавливается на
     * первом сработавшем уровне:
     * <ol>
     *   <li>зашитый псевдоним;</li>
     *   <li>точное имя из «Пуска»;</li>
     *   <li>то же самое, но с транслитерацией («телеграм» -> «telegram»);</li>
     *   <li>приблизительное совпадение — по началу строки, по слову, по
     *       подстроке и в последнюю очередь по расстоянию Левенштейна
     *       («дискорд» -> «discord»).</li>
     * </ol>
     */
    public static Optional<Target> resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) return Optional.empty();
        // «Документы\ФаустТест» — папка ВНУТРИ разрешённой. Нужна многоэтапным
        // задачам: на прогоне 30.09 агент потратил 20 шагов, чтобы найти в
        // Проводнике папку «Загрузки» и файл в ней, — а открыть её сразу можно
        // одним действием. Выйти за пределы разрешённой папки через «..» нельзя.
        Optional<Target> sub = subfolder(rawName);
        if (sub.isPresent()) return sub;
        String query = norm(rawName);
        if (query.isEmpty()) return Optional.empty();

        Target exact = BY_ALIAS.get(query);
        if (exact != null) return Optional.of(exact);

        Map<String, Target.StartMenuApp> installed = installedAppsCache();
        if (installed.isEmpty()) return Optional.empty();

        Target.StartMenuApp byName = installed.get(query);
        if (byName != null) return Optional.of(byName);

        String latin = translit(query);
        if (!latin.equals(query)) {
            Target.StartMenuApp byTranslit = installed.get(latin);
            if (byTranslit != null) return Optional.of(byTranslit);
        }

        // Приблизительный поиск: считаем «штраф» каждому кандидату и берём
        // наименьший. Кириллический и транслитерированный варианты запроса
        // соревнуются на равных — побеждает тот, что подошёл точнее.
        Target.StartMenuApp best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Map.Entry<String, Target.StartMenuApp> e : installed.entrySet()) {
            int score = Math.min(score(e.getKey(), query), score(e.getKey(), latin));
            if (score < bestScore) {
                bestScore = score;
                best = e.getValue();
            }
        }
        return Optional.ofNullable(bestScore == Integer.MAX_VALUE ? null : best);
    }

    /**
     * Штраф за несовпадение имени приложения с запросом; меньше — лучше,
     * {@link Integer#MAX_VALUE} — не подходит совсем.
     *
     * Разница в длине добавляется к штрафу намеренно: из «Telegram» и
     * «Telegram Web Preview» по запросу «telegram» нужен первый.
     */
    private static int score(String appName, String query) {
        if (query.length() < 3) return Integer.MAX_VALUE;   // «вс», «r» подошли бы ко всему подряд
        if (appName.equals(query)) return 0;
        if (appName.startsWith(query)) return 10 + (appName.length() - query.length());

        for (String word : appName.split(" ")) {
            if (word.equals(query)) return 20 + (appName.length() - query.length());
        }
        if (appName.contains(query)) return 40 + (appName.length() - query.length());
        // Обратное вхождение («телеграм десктоп» содержит «телеграм») — только
        // для длинных имён, иначе короткое «FAQ» ловило бы половину фраз.
        if (appName.length() >= 4 && query.contains(appName)) return 60 + (query.length() - appName.length());

        // Последний шанс: опечатка или неточная транслитерация. Допуск растёт
        // с длиной запроса — так «diskord» находит «discord», а «stim»
        // находит «steam» (транслитерация «еа»->«и» съедает два символа),
        // но бессмысленный набор букв по-прежнему не находит ничего.
        int allowed = (query.length() + 2) / 3;
        int distance = levenshtein(appName, query, allowed);
        if (distance <= allowed) return 100 + distance;

        for (String word : appName.split(" ")) {
            int d = levenshtein(word, query, allowed);
            if (d <= allowed) return 120 + d;
        }
        return Integer.MAX_VALUE;
    }

    /** Расстояние Левенштейна с ранним выходом: считать больше, чем allowed, незачем. */
    private static int levenshtein(String a, String b, int allowed) {
        if (Math.abs(a.length() - b.length()) > allowed) return allowed + 1;
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > allowed) return allowed + 1;   // дальше будет только хуже
            int[] swap = prev; prev = curr; curr = swap;
        }
        return prev[b.length()];
    }

    /** «Папка\подпапка» -> существующая подпапка внутри разрешённой папки, иначе пусто. */
    private static Optional<Target> subfolder(String rawName) {
        String s = rawName.strip().replace('/', '\\');
        int cut = s.indexOf('\\');
        if (cut <= 0 || cut == s.length() - 1) return Optional.empty();
        Target root = BY_ALIAS.get(norm(s.substring(0, cut)));
        if (!(root instanceof Target.Folder folder)) return Optional.empty();
        Path inside = folder.path().resolve(s.substring(cut + 1).strip()).normalize();
        if (!inside.startsWith(folder.path()) || !java.nio.file.Files.isDirectory(inside)) return Optional.empty();
        return Optional.of(new Target.Folder(inside));
    }

    /** Нижний регистр, ё→е, долой пунктуацию и двойные пробелы. Не private — нужна и FileTools. */
    static String norm(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^а-яa-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Грубая транслитерация кириллицы в латиницу — чтобы «телеграм» нашёл
     * «Telegram». Точность тут не нужна: результат всё равно проходит через
     * приблизительное сравнение, которое прощает пару символов разницы.
     */
    static String translit(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int i = "абвгдежзийклмнопрстуфхцчшщъыьэюя".indexOf(c);
            out.append(i < 0 ? String.valueOf(c) : LATIN[i]);
        }
        return out.toString();
    }

    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"
    };

    // --- сообщение об ошибке --------------------------------------------

    /** Что перечислить пользователю, когда программа не нашлась. */
    public static String describeKnown() {
        String aliases = String.join(", ", new TreeSet<>(BY_ALIAS.keySet()));
        // Намеренно НЕ форсируем чтение «Пуска» ради текста ошибки: если
        // каталог ещё не строился, значит запрос разрешился зашитым
        // псевдонимом и грузить 200+ записей сейчас не за чем.
        Map<String, Target.StartMenuApp> installed = installedApps;
        if (installed == null || installed.isEmpty()) return aliases;
        // Поимённо не перечисляем: приложений бывает под сотню, такое
        // сообщение невозможно ни прочитать, ни произнести голосом.
        return aliases + " — а также " + installed.size()
                + " установленных приложений (назовите точнее, например «телеграм» или «дискорд»)";
    }
}
