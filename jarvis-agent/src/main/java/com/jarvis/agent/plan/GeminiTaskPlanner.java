package com.jarvis.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.agent.llm.RelayLlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Планировщик на Gemini. Как и GeminiVisionAgent, ходит в модель не напрямую,
 * а через прокси релея (ключ только на релее — инвариант 3). Запрос текстовый,
 * без картинки: планировщику экран не нужен, ему нужна только просьба.
 */
public class GeminiTaskPlanner implements TaskPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Главное в промпте — что умеет каждый вид подзадачи и чего НЕ умеет.
     * Самая частая ошибка, которую он предотвращает: отправить сообщение
     * «через WhatsApp Web» в браузере агента, где никто не вошёл в аккаунт.
     */
    private static final String RULES = """
            Виды подзадач:
              {"scope":"browser","goal":"..."} — действие на сайте в браузере агента. ВАЖНО: это
                  отдельный браузер с чистым профилем, в нём НЕ выполнен вход ни на один сайт
                  (почта, соцсети, WhatsApp Web, Telegram Web недоступны). Годится для открытых
                  сайтов: найти, прочитать, скачать файл. Скачанное попадает в папку «Загрузки»,
                  а точный путь к файлу следующие подзадачи получат автоматически.
              {"scope":"open_app","app":"<название>"} — открыть программу на ПК (WhatsApp, Telegram,
                  Проводник, Блокнот ...). Выполняется точно и без ИИ — ставь его перед работой
                  в любой программе. Умеет открыть и ПАПКУ сразу: "app":"Загрузки" или
                  "app":"Документы\\Диплом" — Проводник откроется прямо в ней. Для работы с файлами
                  это намного быстрее, чем искать папку в Проводнике.
              {"scope":"screen","goal":"..."} — действие внутри уже открытой программы на ПК.
                  В установленных программах (WhatsApp, Telegram) пользователь уже вошёл в аккаунт.
              {"scope":"run_script","script":"<название из списка ДОВЕРЕННЫХ СКРИПТОВ>"} — запустить скрипт,
                  который пользователь сам написал (поднять сервер, собрать проект). Выполняется кодом.
                  Только из списка и только если просьба про это; другие команды запрещены.
              {"scope":"git","repo":"<название из списка РЕПОЗИТОРИИ>","action":"status|pull|commit|push|log|branch|discard|run",
               "message":"<сообщение коммита, для commit; можно не писать после code>","name":"<имя ветки, для branch>",
               "command":"<название из списка КОМАНДЫ ПРОЕКТОВ, для run>"}
                  — git в разрешённом репозитории. log — последние коммиты; branch — перейти на ветку,
                  а если её нет — создать; discard — откатить несохранённые правки (спросит человека);
                  run — прогнать разрешённую команду проекта (тесты, сборка) и дождаться итога.
                  Если после code будет commit, а у проекта есть команда тестов или проверки, —
                  поставь run с ней между code и commit: упадёт — агент сам попробует исправить.
                  Выполняется кодом, без терминала. Коммит берёт ВСЕ изменения в папке проекта.
                  Сообщение коммита пиши коротко по-русски, по смыслу изменения («Порт web 8090»).
              {"scope":"code","repo":"<название из списка РЕПОЗИТОРИИ>","goal":"..."} — правка файлов
                  проекта агентом для кода: поменять порт, добавить переменную, исправить строку.
                  В goal — что именно изменить, со всеми значениями. Он только правит файлы: не
                  собирает, не запускает, не коммитит — для этого есть git (commit, push, run).
                  Для commit и push НЕ пиши "confirm": код сам спросит человека и покажет изменения.
                  Работа с кодом — ТОЛЬКО через git и code, не через screen в редакторе или IDE.
              {"scope":"wait","goal":"<чего ждём, по-человечески>","process":"<имя процесса>",
               "expect_file":"<Папка>\\<файл>","check":"<что должно стать видно в окне>","minutes":N}
                  — подождать, пока выполнится условие (одно из трёх): закроется процесс
                  («пока закончится сборка» — process "java" или "gradle"), появится файл, станет видно
                  нужное в окне. Ничего не делает, только ждёт. minutes — сколько ждать (1–60).
                  Для «когда закончится — напиши мне» достаточно wait: итог задачи и так придёт человеку.
              {"scope":"for_each","list":"<название из СПИСКОВ ПОЛУЧАТЕЛЕЙ>","steps":[...]}
                  — одни и те же шаги для каждого человека из списка («отправь одногруппникам»).
                  В шагах вместо имени пиши {имя} — код сам повторит их для каждого и подставит имена.
                  open_app ставь ДО for_each, а не внутрь. Рассылка — ТОЛЬКО по спискам: «всем
                  контактам», «во все чаты», «всем в группе» без списка — give_up.

            РЕЦЕПТ: если в просьбе пользователь САМ перечислил шаги («1. … 2. …»), это его рецепт.
            Составь план строго по этим шагам и в том же порядке — одна-две подзадачи на шаг.
            Не пропускай шаги и не добавляй своих, кроме open_app перед работой в программе.

            Правила:
              - каждая подзадача — в ОДНОЙ программе, и по ней понятно, когда она готова;
              - одинаковые действия для разных людей или файлов — отдельными подзадачами
                («отправить файл Лере», «отправить файл Маше»), а не одной общей;
              - в goal пиши всё, что нужно исполнителю: имя контакта, название файла, текст
                сообщения — он НЕ видит исходную просьбу целиком;
              - если данные появятся только по ходу (имя скачанного файла), так и пиши:
                «прикрепи файл, скачанный на прошлом шаге»;
              - исполнитель в программах умеет найти файл по имени (Загрузки, Документы, Рабочий
                стол, Изображения) и вставить его по пути, без окна «Открыть». Поэтому «прикрепить
                файл и отправить Лере» — это ОДНА подзадача в мессенджере, отдельный шаг «найти
                файл» не нужен;
              - терминалы (PowerShell, cmd, Windows Terminal) и системные инструменты агенту ЗАПРЕЩЕНЫ —
                не планируй их; всё делается обычными программами;
              - zip-архив исполнитель в программах распаковывает ОДНИМ действием: достаточно screen-
                подзадачи «распакуй архив <имя> в папку <папка>» (после open_app любой папки);
              - задачи бывают любые, не только переписка: скопировать файлы в папку в Проводнике,
                записать найденное в Блокнот и сохранить, открыть документ и что-то в нём сделать;
              - простая просьба в одной программе — ОДНА подзадача, не усложняй;
              - не больше 8 подзадач;
              - если в просьбе есть необратимое действие (отправить, удалить, опубликовать,
                купить), заполни "confirm" — ОДИН вопрос-подтверждение, где перечислено, что и
                кому будет сделано. Иначе "confirm":"".

            Проверка результата — к подзадачам browser и screen добавляй, если результат можно проверить:
              "expect_file":"<Папка>\\<имя файла>" — файл, который должен появиться после шага
                  (папка: Рабочий стол, Документы, Загрузки или Изображения);
              "check":"<что должно быть видно в окне программы после шага>" — только для screen,
                  коротко и проверяемо: «в чате Избранное последнее сообщение — файл report.pdf»,
                  «заголовок окна Блокнота — курс.txt».
              Проверки делает отдельный проверяющий: если результата нет, план перестроится.
              Не пиши проверку, если результат ничем не виден (например, «прочитай курс»).
              Для СКАЧИВАНИЯ expect_file не пиши: имя файла заранее неизвестно, а что скачалось,
              агент проверяет сам. В проверках не называй точное имя скачанного файла.
              expect_file пиши, только когда имя и место файла задаёт САМА просьба («сохрани на рабочий
              стол как курс.txt»). Не угадывай, что лежит внутри архива и в каких подпапках.

            Ответь СТРОГО одним JSON без markdown, одним из трёх видов:
              {"steps":[...],"confirm":"..."}
              {"ask":"<вопрос пользователю>","options":["...","..."]}
                  — если без ответа человека план не составить: не сказано, какой файл, кому,
                    что написать. Ответ придёт в разделе УТОЧНЕНИЯ, и ты составишь план заново.
                    Не спрашивай то, что агент найдёт сам (где лежит файл, как открыть программу).
                    Не уточняй то, что можно выполнить буквально: «напиши список покупок» — это
                    напечатать сами слова «список покупок», а не выяснять, какие продукты.
                    Спрашивай, только если без ответа действие вообще не выполнить.
              {"give_up":"<причина>"}
                  — если задача невыполнима или опасна: пароли, платежи, рассылка десяткам людей.
            """;

    private final RelayLlmClient relayLlm;
    /** Какие программы есть на этом ПК (из меню «Пуск») — пусто, если неизвестно. */
    private final java.util.function.Supplier<List<String>> installedApps;

    /** Без списка программ — для проверок промпта, где он не нужен. */
    public GeminiTaskPlanner(RelayLlmClient relayLlm) {
        this(relayLlm, List::of);
    }

    /**
     * @param installedApps названия программ из «Пуска». Зачем: без этого
     *                      планировщик уверенно планирует «открой Word» на ПК,
     *                      где стоит только LibreOffice, — и план разваливается
     *                      на первом же шаге.
     */
    public GeminiTaskPlanner(RelayLlmClient relayLlm, java.util.function.Supplier<List<String>> installedApps) {
        this(relayLlm, installedApps, List::of);
    }

    /** @param scripts названия доверенных скриптов пользователя (ScriptCatalog) */
    public GeminiTaskPlanner(RelayLlmClient relayLlm, java.util.function.Supplier<List<String>> installedApps,
                             java.util.function.Supplier<List<String>> scripts) {
        this(relayLlm, installedApps, scripts, List::of);
    }

    /** @param repos названия разрешённых git-репозиториев (RepoCatalog) — без путей */
    public GeminiTaskPlanner(RelayLlmClient relayLlm, java.util.function.Supplier<List<String>> installedApps,
                             java.util.function.Supplier<List<String>> scripts,
                             java.util.function.Supplier<List<String>> repos) {
        this.relayLlm = relayLlm;
        this.installedApps = installedApps;
        this.scripts = scripts;
        this.repos = repos;
    }

    private final java.util.function.Supplier<List<String>> scripts;
    private final java.util.function.Supplier<List<String>> repos;
    /** Списки получателей (RecipientLists) — название -> люди. */
    private java.util.function.Supplier<Map<String, List<String>>> lists = Map::of;

    /** Команды проектов (RepoCommands) — репозиторий -> названия команд, без командных строк. */
    private java.util.function.Supplier<Map<String, List<String>>> repoCommands = Map::of;

    /** Подключить названия разрешённых команд проектов — для шагов git run. */
    public GeminiTaskPlanner withRepoCommands(java.util.function.Supplier<Map<String, List<String>>> commands) {
        if (commands != null) this.repoCommands = commands;
        return this;
    }

    /** Подключить списки получателей для шагов «для каждого». */
    public GeminiTaskPlanner withLists(java.util.function.Supplier<Map<String, List<String>>> lists) {
        if (lists != null) this.lists = lists;
        return this;
    }

    @Override
    public PlannerReply plan(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining) {
        try {
            List<String> apps;
            List<String> scriptNames;
            try {
                apps = installedApps.get();
            } catch (Exception e) {
                apps = List.of(); // список программ — подсказка, без него план всё равно составится
            }
            try {
                scriptNames = scripts.get();
            } catch (Exception e) {
                scriptNames = List.of();
            }
            List<String> repoNames;
            try {
                repoNames = repos.get();
            } catch (Exception e) {
                repoNames = List.of();
            }
            Map<String, List<String>> recipientLists;
            try {
                recipientLists = lists.get();
            } catch (Exception e) {
                recipientLists = Map.of();
            }
            Map<String, List<String>> commands;
            try {
                commands = repoCommands.get();
            } catch (Exception e) {
                commands = Map.of();
            }
            return parse(relayLlm.generate(buildPrompt(goal, done, failed, remaining, apps, scriptNames, repoNames,
                    recipientLists, commands), null), recipientLists);
        } catch (Exception e) {
            // Сеть, квота, кривой ответ — не беда: вызывающий код выполнит
            // задачу прежним способом, одним циклом без плана.
            System.err.println("планировщик недоступен: " + e.getMessage());
            return null;
        }
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining) {
        return buildPrompt(goal, done, failed, remaining, List.of());
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining,
                              List<String> apps) {
        return buildPrompt(goal, done, failed, remaining, apps, List.of());
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining,
                              List<String> apps, List<String> scriptNames) {
        return buildPrompt(goal, done, failed, remaining, apps, scriptNames, List.of());
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining,
                              List<String> apps, List<String> scriptNames, List<String> repoNames) {
        return buildPrompt(goal, done, failed, remaining, apps, scriptNames, repoNames, Map.of());
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining,
                              List<String> apps, List<String> scriptNames, List<String> repoNames,
                              Map<String, List<String>> recipientLists) {
        return buildPrompt(goal, done, failed, remaining, apps, scriptNames, repoNames, recipientLists, Map.of());
    }

    static String buildPrompt(String goal, List<StepResult> done, StepResult failed, List<PlanStep> remaining,
                              List<String> apps, List<String> scriptNames, List<String> repoNames,
                              Map<String, List<String>> recipientLists, Map<String, List<String>> repoCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты — планировщик для агента, который управляет компьютером пользователя (Windows).\n")
          .append("Разбей просьбу на подзадачи. Каждую выполнит отдельный исполнитель, который видит\n")
          .append("только ОДНУ программу за раз и умеет кликать, печатать и нажимать клавиши.\n\n")
          .append("ПРОСЬБА: ").append(goal).append("\n\n");

        if (!apps.isEmpty()) {
            sb.append("ПРОГРАММЫ, УСТАНОВЛЕННЫЕ НА ЭТОМ ПК (для open_app бери название отсюда; если нужной\n")
              .append("программы нет — возьми похожую из списка, а если подходящей нет вовсе — give_up):\n")
              .append(String.join(", ", apps)).append("\n")
              .append("Всегда есть также: Проводник, Блокнот, Калькулятор, Paint.\n\n");
        }
        sb.append("ДОВЕРЕННЫЕ СКРИПТЫ ПОЛЬЗОВАТЕЛЯ (для run_script): ")
          .append(scriptNames.isEmpty() ? "нет" : String.join(", ", scriptNames)).append("\n");
        sb.append("РЕПОЗИТОРИИ (для git и code): ")
          .append(repoNames.isEmpty() ? "нет — git и code недоступны" : String.join(", ", repoNames)).append("\n");
        if (!repoNames.isEmpty()) {
            sb.append("КОМАНДЫ ПРОЕКТОВ (для git run): ");
            if (repoCommands.isEmpty()) {
                sb.append("нет — run недоступен");
            } else {
                List<String> parts = new ArrayList<>();
                repoCommands.forEach((repo, names) -> parts.add(repo + ": " + String.join(", ", names)));
                sb.append(String.join("; ", parts));
            }
            sb.append("\n");
        }
        sb.append("СПИСКИ ПОЛУЧАТЕЛЕЙ (для for_each): ");
        if (recipientLists.isEmpty()) {
            sb.append("нет — рассылка недоступна");
        } else {
            List<String> parts = new ArrayList<>();
            recipientLists.forEach((name, people) -> parts.add(name + " (" + String.join(", ", people) + ")"));
            sb.append(String.join("; ", parts));
        }
        sb.append("\n\n");

        if (failed != null && failed.summary() != null && failed.summary().startsWith(PlanRunner.CORRECTION_MARK)) {
            // Перестройка по ПОПРАВКЕ человека — это не неудача. Обычная ветка ниже
            // велела бы «сделать ту же подзадачу по-другому», а человек как раз
            // сказал, что делать надо другое. Сама поправка уже в ПРОСЬБЕ (раздел
            // «ПОПРАВКИ» — его добавляет Clarifications.goalFor).
            sb.append("ПЛАН УЖЕ ВЫПОЛНЯЛСЯ, И ЧЕЛОВЕК ПРИСЛАЛ ПОПРАВКУ ПО ХОДУ ЗАДАЧИ.\n");
            if (!done.isEmpty()) {
                sb.append("Уже сделано (НЕ повторяй):\n");
                for (int i = 0; i < done.size(); i++) {
                    StepResult r = done.get(i);
                    sb.append("  ").append(i + 1).append(". ").append(r.step().describe())
                      .append(" — итог: ").append(r.summary()).append("\n");
                }
            }
            sb.append("Поправка пришла во время подзадачи: ").append(failed.step().describe())
              .append(" — ").append(failed.summary().substring(PlanRunner.CORRECTION_MARK.length())).append("\n");
            if (!remaining.isEmpty()) {
                sb.append("Оставалось по старому плану:\n");
                for (PlanStep s : remaining) sb.append("  - ").append(s.describe()).append("\n");
            }
            sb.append("Составь план ТОЛЬКО на оставшуюся часть, С УЧЁТОМ ПОПРАВКИ: где она противоречит\n")
              .append("просьбе — делай как в поправке. Сделанное не повторяй. Если по старой версии уже\n")
              .append("начато то, что поправка отменяет, и это можно исправить (стереть ещё НЕ отправленный\n")
              .append("текст, выбрать другой чат) — начни с исправляющей подзадачи. Уже отправленное\n")
              .append("сообщение сам не удаляй — если это важно, спроси человека (ask).\n")
              .append("Если после поправки делать больше нечего — give_up с причиной, начинающейся словами\n")
              .append("«НЕЧЕГО ДЕЛАТЬ».\n\n");
        } else if (failed != null) {
            // Перепланирование: модели нужно знать, что уже сделано (иначе она
            // начнёт с начала и, например, скачает файл второй раз) и почему
            // сломался шаг (иначе предложит тот же самый путь).
            sb.append("ПЛАН УЖЕ ВЫПОЛНЯЛСЯ, НО ОДНА ПОДЗАДАЧА НЕ УДАЛАСЬ.\n");
            if (!done.isEmpty()) {
                sb.append("Уже сделано (НЕ повторяй):\n");
                for (int i = 0; i < done.size(); i++) {
                    StepResult r = done.get(i);
                    sb.append("  ").append(i + 1).append(". ").append(r.step().describe())
                      .append(" — итог: ").append(r.summary()).append("\n");
                }
            }
            sb.append("Не удалось: ").append(failed.step().describe())
              .append(" — причина: ").append(failed.summary()).append("\n");
            if (!remaining.isEmpty()) {
                sb.append("Оставалось по старому плану:\n");
                for (PlanStep s : remaining) sb.append("  - ").append(s.describe()).append("\n");
            }
            sb.append("Составь план ТОЛЬКО на оставшуюся часть. Неудавшуюся подзадачу попробуй\n")
              .append("сделать по-другому (другая формулировка, другой путь, другая программа).\n")
              .append("Если продолжать бессмысленно — give_up, если нужен человек — ask.\n\n");
        }

        sb.append(RULES);
        return sb.toString();
    }

    /**
     * Разбор ответа модели. Не private — ради тестов.
     *
     * @return ответ или null, если из текста ничего осмысленного не извлечь
     */
    static PlannerReply parse(String text) throws Exception {
        return parse(text, Map.of());
    }

    /**
     * @param recipientLists списки получателей — для шагов for_each
     */
    static PlannerReply parse(String text, Map<String, List<String>> recipientLists) throws Exception {
        String clean = text.trim();
        if (clean.startsWith("```")) { // модель всё-таки обернула в ```json ... ```
            clean = clean.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        JsonNode n = MAPPER.readTree(clean);

        if (n.hasNonNull("give_up") && !n.get("give_up").asText().isBlank()) {
            return new PlannerReply.GiveUp(n.get("give_up").asText().strip());
        }
        if (n.hasNonNull("ask") && !n.get("ask").asText().isBlank()) {
            return new PlannerReply.Ask(n.get("ask").asText().strip(), options(n.get("options")));
        }

        List<PlanStep> steps = new ArrayList<>();
        int listSteps = 0;
        List<String> recipients = new ArrayList<>();
        for (JsonNode s : n.path("steps")) {
            if ("for_each".equalsIgnoreCase(s.path("scope").asText(""))) {
                // «Для каждого из списка» размножает КОД, а не модель: модель
                // могла бы пропустить человека или выдумать лишнего. Она называет
                // только список; кто в нём — знает файл lists.txt.
                String listName = s.path("list").asText("").strip();
                Map.Entry<String, List<String>> list = findList(recipientLists, listName);
                if (list == null) {
                    return new PlannerReply.GiveUp("нет списка получателей «" + listName + "»"
                            + (recipientLists.isEmpty() ? "; списки задаются в ~/.jarvis-agent/lists.txt"
                                                        : "; есть: " + String.join(", ", recipientLists.keySet())));
                }
                List<PlanStep> template = new ArrayList<>();
                for (JsonNode inner : s.path("steps")) {
                    PlanStep step = step(inner);
                    // open_app внутри цикла не нужен (программа уже открыта), а for_each в for_each — тем более.
                    if (step != null && step.kind() != PlanStep.Kind.OPEN_APP) template.add(step);
                }
                if (template.isEmpty()) continue;
                for (String person : list.getValue()) {
                    for (PlanStep t : template) {
                        steps.add(withName(t, person));
                        listSteps++;
                    }
                    if (!recipients.contains(person)) recipients.add(person);
                }
                continue;
            }
            PlanStep step = step(s);
            // Непонятную подзадачу пропускаем, а не роняем весь план: если в
            // итоге не останется ни одной — вернём null, и сработает прежний путь.
            if (step != null) steps.add(step);
        }
        if (steps.isEmpty()) return null;
        String confirm = n.path("confirm").asText("").strip();
        if (!recipients.isEmpty()) {
            // Подтверждение рассылки всегда перечисляет всех поимённо — это пишет
            // код, а не модель: человек должен видеть, кому именно уйдёт.
            String who = "Получатели (" + recipients.size() + "): " + String.join(", ", recipients) + ".";
            confirm = confirm.isEmpty() ? "Выполнить для каждого? " + who : confirm + " " + who;
        }
        return new PlannerReply.Steps(steps, confirm, listSteps);
    }

    private static Map.Entry<String, List<String>> findList(Map<String, List<String>> lists, String name) {
        if (name.isEmpty()) return null;
        for (Map.Entry<String, List<String>> e : lists.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e;
        }
        // Падеж: «одногруппникам» -> «одногруппники». Только если вариант один.
        String stem = name.toLowerCase(Locale.ROOT);
        stem = stem.length() > 4 ? stem.substring(0, stem.length() - 2) : stem;
        Map.Entry<String, List<String>> found = null;
        for (Map.Entry<String, List<String>> e : lists.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).startsWith(stem)) {
                if (found != null) return null;
                found = e;
            }
        }
        return found;
    }

    /** Подставить имя человека вместо {имя} / {name} во все поля шага. */
    private static PlanStep withName(PlanStep t, String person) {
        java.util.function.UnaryOperator<String> sub = v -> v == null ? null
                : v.replace("{имя}", person).replace("{Имя}", person).replace("{name}", person);
        String goal = sub.apply(t.goal());
        // Модель забыла {имя} — допишем, кому этот шаг, иначе все копии были бы одинаковыми.
        if (goal != null && goal.equals(t.goal()) && t.kind() != PlanStep.Kind.WAIT) goal = goal + " (для: " + person + ")";
        return new PlanStep(t.kind(), goal, sub.apply(t.app()), sub.apply(t.check()), sub.apply(t.expectFile()),
                t.waitMinutes());
    }

    private static PlanStep step(JsonNode s) {
        String scope = s.path("scope").asText("").toLowerCase(Locale.ROOT);
        String goal = s.path("goal").asText("").strip();
        String app = s.path("app").asText("").strip();
        String check = s.path("check").asText("");
        String expectFile = s.path("expect_file").asText("");
        return switch (scope) {
            case "browser" -> goal.isEmpty() ? null : PlanStep.browser(goal).withChecks(null, expectFile);
            case "screen" -> goal.isEmpty() ? null : PlanStep.screen(goal).withChecks(check, expectFile);
            // Модель иногда пишет название программы в goal, а не в app — принимаем и так.
            case "open_app", "app" -> {
                String name = !app.isEmpty() ? app : goal;
                yield name.isEmpty() ? null : PlanStep.openApp(name);
            }
            case "run_script", "script" -> {
                String script = s.path("script").asText("").strip();
                String name = !script.isEmpty() ? script : !app.isEmpty() ? app : goal;
                yield name.isEmpty() ? null : PlanStep.runScript(name);
            }
            case "git" -> {
                String repo = s.path("repo").asText("").strip();
                String action = s.path("action").asText("").strip().toLowerCase(Locale.ROOT);
                if (!List.of("status", "pull", "commit", "push", "log", "branch", "discard", "run").contains(action)) yield null;
                if (action.equals("run")) {
                    // Модель называет команду только по названию; есть ли такая у
                    // проекта, проверит DevTools при выполнении (и назовёт доступные).
                    String command = s.path("command").asText("").strip();
                    if (command.isEmpty()) command = s.path("name").asText("").strip();
                    yield command.isEmpty() ? null : PlanStep.git(repo, "run: " + command);
                }
                if (action.equals("branch")) {
                    String name = s.path("name").asText("").strip();
                    if (name.isEmpty()) yield null;
                    action = "branch: " + name;
                }
                if (action.equals("commit")) {
                    String msg = s.path("message").asText("").strip();
                    action = msg.isEmpty() ? "commit" : "commit: " + msg;
                }
                yield PlanStep.git(repo, action);
            }
            case "code" -> goal.isEmpty() ? null : PlanStep.code(s.path("repo").asText("").strip(), goal);
            case "wait" -> {
                String process = s.path("process").asText("").strip();
                Integer minutes = s.hasNonNull("minutes") ? s.get("minutes").asInt(0) : null;
                if (minutes != null && minutes <= 0) minutes = null;
                if (process.isEmpty() && check.isBlank() && expectFile.isBlank()) yield null; // ждать нечего
                yield PlanStep.waitFor(goal.isEmpty() ? "условие" : goal, process, check, expectFile, minutes);
            }
            default -> null;
        };
    }

    /** Варианты ответа: массив строк или одна строка «Да / Нет». */
    private static List<String> options(JsonNode o) {
        List<String> out = new ArrayList<>();
        if (o == null || o.isNull()) return out;
        if (o.isArray()) {
            o.forEach(x -> out.add(x.asText()));
        } else {
            for (String part : o.asText().split("[/|;,]")) out.add(part.trim());
        }
        return out;
    }
}
