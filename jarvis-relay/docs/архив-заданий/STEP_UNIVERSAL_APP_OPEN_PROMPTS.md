# Фича: универсальное открытие приложений (Telegram и всё, что видно в «Пуск»)

## Общий контекст

Прочитал `AppCatalog.java` — причина, почему `open_app` не может открыть
Telegram (и вообще ничего, кроме пяти зашитых программ), не баг, а
сознательное архитектурное решение: закрытый статический словарь
`alias -> executable` (блокнот, калькулятор, проводник, paint, диспетчер
задач) плюс браузер и четыре системные папки. Комментарий в коде прямо
говорит: «сознательно НЕ произвольный путь с диска — только то, что есть
в словаре». Значит просто раскомментировать/добавить путь к Telegram
руками — плохое решение: сработает для одной программы, а через месяц
понадобится Discord, потом VS Code, и так до бесконечности.

**Решение — не расширять список руками, а искать по тому же источнику,
которым пользуется сам Windows.** Команда PowerShell `Get-StartApps`
отдаёт список ВСЕГО, что видно в меню «Пуск» — и обычные `.exe`-программы,
и магазинные (Telegram через Store туда тоже попадёт), парой
`Name`/`AppID`. Запускается любая запись оттуда универсально через
`explorer.exe shell:AppsFolder\<AppID>` — тем же механизмом, которым
Windows открывает найденное в собственном поиске.

**Почему это не ломает модель безопасности.** Whitelist на релее
по-прежнему решает, разрешена ли команда `open_app` вообще (не трогаем).
`AppCatalog` по-прежнему остаётся ЗАКРЫТОЙ границей — просто закрытое
множество теперь не «пять программ, которые я вписал в код руками», а
«всё, что уже установлено на этом ПК и видно в Пуск» — то же самое, что
пользователь и так может открыть сам через Win+S. Ни одна LLM-фраза не
получает произвольный путь с диска, как и раньше.

Список подгружается ЛЕНИВО (при первом промахе по статическому словарю)
и кэшируется на всё время работы агента — не тормозит старт, если
`open_app` в сессии вообще не понадобится; плюс один явный «прогрев» при
старте агента (в фоновом потоке, не блокируя остальной запуск), чтобы
самая первая голосовая команда «открой телеграм» не ждала лишнюю секунду
на PowerShell.

Протокол/relay/whitelist НЕ трогаем — `open_app` уже разрешённая команда,
вся правка локальна для `jarvis-agent`. Самая маленькая из фич за
сегодня, поэтому и промптов всего три.

---

## Промпт 1 — `AppCatalog.java`: динамический каталог + нестрогий поиск

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AppCatalog.java`.
Замени содержимое файла целиком:

```java
// было (весь файл)
package com.jarvis.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Словарь разрешённого для open_app — СОЗНАТЕЛЬНО не произвольный путь с
 * диска: команда LLM даёт только имя, а запускаем мы только то, что есть
 * здесь. Это граница безопасности (как CommandWhitelist на релее, только
 * для "какую программу можно открыть"). Расширять — дописывая записи ниже.
 */
public final class AppCatalog {

    /** Что в итоге нужно сделать с найденной записью — решает CommandExecutor. */
    public sealed interface Target {
        record Program(String executable) implements Target {}
        record Folder(Path path) implements Target {}
        record DefaultBrowser() implements Target {}
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

    private AppCatalog() {}

    public static Optional<Target> resolve(String rawName) {
        if (rawName == null) return Optional.empty();
        return Optional.ofNullable(BY_ALIAS.get(rawName.trim().toLowerCase(Locale.ROOT)));
    }

    /** Список известных имён для сообщения об ошибке "не знаю такую программу, могу: ...". */
    public static String describeKnown() {
        return String.join(", ", new TreeSet<>(BY_ALIAS.keySet()));
    }
}
// стало (весь файл)
package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Словарь разрешённого для open_app — СОЗНАТЕЛЬНО не произвольный путь с
 * диска: команда LLM даёт только имя, а запускаем мы только то, что есть
 * здесь. Это граница безопасности (как CommandWhitelist на релее, только
 * для "какую программу можно открыть"). Расширять — дописывая записи ниже.
 *
 * Кроме зашитых записей ниже, каталог динамически умеет ВСЁ, что видно в
 * меню «Пуск» (см. installedAppsCache) — граница безопасности от этого не
 * размывается: это по-прежнему закрытое множество, просто не "то, что я
 * вписал в код руками", а "то, что уже установлено и видно в Пуск" — то
 * же самое, что пользователь и так может открыть сам через Win+S.
 */
public final class AppCatalog {

    /** Что в итоге нужно сделать с найденной записью — решает CommandExecutor. */
    public sealed interface Target {
        record Program(String executable) implements Target {}
        record Folder(Path path) implements Target {}
        record DefaultBrowser() implements Target {}
        /** Найдено динамически через Get-StartApps — и обычный .exe, и магазинное приложение. */
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
     * Кэш "всё из меню Пуск" — строится ОДИН РАЗ за время жизни агента, при
     * первом обращении (лениво) или заранее через warmUp() при старте.
     * null = ещё не строился; пустая карта = строился, но ничего не нашёл
     * или PowerShell недоступен — в обоих случаях больше не пробуем молча
     * на каждый промах, иначе один сломанный PowerShell тормозил бы КАЖДУЮ
     * нераспознанную фразу на 5-10 секунд.
     */
    private static volatile Map<String, Target.StartMenuApp> installedApps;

    private static synchronized Map<String, Target.StartMenuApp> installedAppsCache() {
        if (installedApps != null) return installedApps;
        Map<String, Target.StartMenuApp> found = new LinkedHashMap<>();
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-StartApps | ConvertTo-Json -Compress"
            ).start();
            process.getOutputStream().close();
            String json;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                json = sb.toString();
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            } else if (!json.isBlank()) {
                JsonNode root = new ObjectMapper().readTree(json);
                // Get-StartApps с ровно одним результатом отдаёт объект, а не массив — приводим к единому виду.
                Iterable<JsonNode> items = root.isArray() ? root : List.of(root);
                for (JsonNode item : items) {
                    String name = item.path("Name").asText(null);
                    String appId = item.path("AppID").asText(null);
                    if (name == null || appId == null || name.isBlank() || appId.isBlank()) continue;
                    found.put(name.toLowerCase(Locale.ROOT), new Target.StartMenuApp(appId, name));
                }
            }
        } catch (Exception e) {
            // PowerShell недоступен/упал — не роняем агента, просто динамический каталог остаётся пустым;
            // статические записи (калькулятор и т.д.) продолжают работать как раньше.
            System.err.println("AppCatalog: Get-StartApps недоступен: " + e.getMessage());
        }
        installedApps = found;
        return found;
    }

    /** Прогреть кэш заранее (см. AgentMain) — чтобы первая голосовая команда не ждала PowerShell. Дешёвая, вызывать из фонового потока. */
    public static void warmUp() {
        installedAppsCache();
    }

    private AppCatalog() {}

    public static Optional<Target> resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) return Optional.empty();
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);

        Target exact = BY_ALIAS.get(normalized);
        if (exact != null) return Optional.of(exact);

        Map<String, Target.StartMenuApp> installed = installedAppsCache();
        Target.StartMenuApp installedExact = installed.get(normalized);
        if (installedExact != null) return Optional.of(installedExact);

        // Нестрогое совпадение в обе стороны: "телеграм" находит "Telegram Desktop",
        // а "adobe" — что угодно с этим словом в названии. Берём первое совпадение
        // по порядку из Get-StartApps — если приложений с похожими именами несколько,
        // может понадобиться формулировка точнее.
        for (Map.Entry<String, Target.StartMenuApp> entry : installed.entrySet()) {
            if (entry.getKey().contains(normalized) || normalized.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** Список известных имён для сообщения об ошибке "не знаю такую программу, могу: ...". */
    public static String describeKnown() {
        String staticNames = String.join(", ", new TreeSet<>(BY_ALIAS.keySet()));
        Map<String, Target.StartMenuApp> installed = installedApps; // не форсируем загрузку только ради текста ошибки
        if (installed == null || installed.isEmpty()) return staticNames;
        return staticNames + " — а также " + installed.size()
                + " установленных приложений (назовите точнее, например «телеграм» или «vs code»)";
    }
}
```

---

## Промпт 2 — `CommandExecutor.java`: запуск найденного через `shell:AppsFolder`

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/CommandExecutor.java`

```java
// было
            switch (target.get()) {
                case AppCatalog.Target.Program p -> new ProcessBuilder(p.executable()).start();
                case AppCatalog.Target.Folder f -> new ProcessBuilder("explorer.exe", f.path().toString()).start();
                case AppCatalog.Target.DefaultBrowser ignored -> openUrl("about:blank");
            }
// стало
            switch (target.get()) {
                case AppCatalog.Target.Program p -> new ProcessBuilder(p.executable()).start();
                case AppCatalog.Target.Folder f -> new ProcessBuilder("explorer.exe", f.path().toString()).start();
                case AppCatalog.Target.DefaultBrowser ignored -> openUrl("about:blank");
                case AppCatalog.Target.StartMenuApp a ->
                        new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + a.appId()).start();
            }
```

Больше в этом файле трогать ничего не нужно — `openApp()` уже вызывает
`AppCatalog.resolve(appName)` и `AppCatalog.describeKnown()` как раньше,
обе функции сами стали "умнее" после Промпта 1.

---

## Промпт 3 — `AgentMain.java`: прогреть кэш при старте (не обязательно, но приятно)

Файл: `jarvis-agent/src/main/java/com/jarvis/agent/AgentMain.java`

```java
// было
        CommandExecutor executor = new CommandExecutor(config, tokenStore, tray, sender);

        // Первый запуск без сохранённого токена и без кода аргументом — раньше
// стало
        CommandExecutor executor = new CommandExecutor(config, tokenStore, tray, sender);

        // Прогреваем каталог установленных программ в фоне (Get-StartApps занимает
        // секунду-другую) — чтобы САМАЯ ПЕРВАЯ команда "открой телеграм" за сессию
        // не ждала PowerShell. Демон-поток: не мешает JVM завершиться, если что-то
        // пойдёт не так — AppCatalog.warmUp() сама ловит все исключения.
        Thread appCatalogWarmup = new Thread(AppCatalog::warmUp, "app-catalog-warmup");
        appCatalogWarmup.setDaemon(true);
        appCatalogWarmup.start();

        // Первый запуск без сохранённого токена и без кода аргументом — раньше
```

---

## Проверка

- [ ] Собирается `jarvis-agent`.
- [ ] Старые команды не сломались: «открой блокнот», «калькулятор», «загрузки» — работают как раньше (статический словарь не тронут).
- [ ] «Открой телеграм» (или как называется установленное у вас приложение) — реально открывается, даже если названо не 1-в-1 (например «телеграм» вместо точного «Telegram Desktop»).
- [ ] Попробовать 2-3 других установленных приложения помимо Telegram (например VS Code, Discord, Spotify — что есть на вашем ПК) голосом по неточному названию.
- [ ] «Открой ыыыфывфыв» (заведомо несуществующее) — приходит понятная ошибка с примерами известных программ, агент не падает и не зависает.
- [ ] Замерить на глаз задержку самой первой команды open_app за сессию — с прогревом (Промпт 3) не должно чувствоваться отдельного зависания на 1-2 секунды; если хочется строго проверить, что прогрев отработал — можно временно добавить лог в `warmUp()`/`installedAppsCache()` и посмотреть консоль агента при старте.
- [ ] Если на ПК стоит политика PowerShell, блокирующая скрипты (маловероятно для домашней машины, но проверить стоит) — агент не падает, просто динамический каталог остаётся пустым, а статические программы (блокнот и т.д.) продолжают открываться.
- [ ] Название с кириллицей и с разным регистром («ТЕЛЕГРАМ», «Телеграм», «телеграм») — работает одинаково (приведение к нижнему регистру).

## Что осталось незакрытым (осознанно)

- Нестрогий поиск — по подстроке в обе стороны, простой и предсказуемый, но не «умный»: если на ПК стоит несколько программ с похожими именами (например два разных мессенджера с «chat» в названии), возьмётся первая по порядку из `Get-StartApps` — не то, что смысловее.
- Кэш строится один раз за жизнь агента — если поставить новую программу, пока Jarvis уже запущен, до перезапуска агента она не найдётся. Можно было бы добавить принудительное обновление по команде, но это отдельная, самостоятельная мелочь.
- `describeKnown()` не перечисляет все установленные приложения поимённо (их может быть 100+) — только количество, чтобы сообщение об ошибке оставалось читаемым/произносимым.
