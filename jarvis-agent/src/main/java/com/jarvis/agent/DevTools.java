package com.jarvis.agent;

import com.jarvis.agent.plan.PlanRunner;
import com.jarvis.agent.plan.PlanStep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструменты разработчика в плане задачи: подзадачи «git» и «правка кода».
 *
 * <p>Пример: «Фауст, поменяй порт в docker-compose демо на 8090, закоммить и
 * запушь». Планировщик (или готовый план с экрана «Проекты») делает три подзадачи:
 * <pre>
 *   [code] демо: поменять порт в docker-compose на 8090   — агент для кода (CodeAgent)
 *   [git]  демо: commit                                   — GitTool, с вопросом и диффом
 *   [git]  демо: push                                     — GitTool, подтверждён вместе с коммитом
 * </pre>
 *
 * <h2>Где спрашивается подтверждение — и почему не в начале плана</h2>
 * Обычный план подтверждается целиком до начала работы. Для кода так нельзя:
 * в начале неизвестно, ЧТО именно изменит агент для кода, а «Да» на
 * неизвестный дифф ничего не стоит. Поэтому вопрос задаётся в момент коммита,
 * и в нём показан настоящий дифф. Правка кода до коммита ничего необратимого
 * не делает — это изменения в рабочей папке, git их видит и может откатить.
 * Если сразу за коммитом в плане стоит push того же репозитория, вопрос
 * один на оба шага, с тремя ответами: «Да» (коммит и push), «Только коммит», «Нет».
 *
 * <p>Объект живёт одну задачу: помнит, какой push уже решён и что сделала правка.
 */
public final class DevTools implements PlanRunner.DevRunner {

    /** Ответы на вопрос о коммите, если следом идёт push. */
    static final String YES = "Да", ONLY_COMMIT = "Только коммит", NO = "Нет";

    /** Репозитории, push которых человек уже подтвердил вместе с коммитом. */
    private final Set<Path> pushApproved = new HashSet<>();
    /** Репозитории, где человек выбрал «только коммит»: push в этой задаче пропускается. */
    private final Set<Path> pushDeclined = new HashSet<>();
    /** Что сказала последняя правка кода — из этого получается сообщение коммита. */
    private String lastCodeSummary;
    /** Последняя задача правки по репозиторию — чтобы при упавших тестах исправлять именно её. */
    private final Map<Path, String> codeTask = new HashMap<>();
    /** Последний прогон команды проверки по репозиторию (null — после правки ещё не гоняли). */
    private final Map<Path, RepoCommands.Run> lastCheck = new HashMap<>();
    /** Сколько попыток исправить по упавшим тестам уже потрачено в этой задаче. */
    private final Map<Path, Integer> fixesUsed = new HashMap<>();

    /** Включить автоисправление по упавшим тестам (по умолчанию да). */
    public static final String AUTO_FIX = "code.autoFix";
    /** Сколько раз агенту для кода исправлять по тексту ошибки (по умолчанию 1, не больше 3). */
    public static final String FIX_ATTEMPTS = "code.fixAttempts";
    /** Добавлять ли прогон тестов в «правку в X: …» перед коммитом (по умолчанию да, если тесты описаны). */
    public static final String TEST_BEFORE_COMMIT = "code.testBeforeCommit";
    /** Модель через релей — для встроенного редактора кода; null — только Claude Code. */
    private final GeminiCodeEditor.Llm llm;

    public DevTools() {
        this(null);
    }

    public DevTools(GeminiCodeEditor.Llm llm) {
        this.llm = llm;
    }

    @Override
    public String run(PlanStep step, List<PlanStep> following, PlanRunner.Confirmer confirm) throws Exception {
        RepoCatalog.Repo repo = repo(step.app());
        if (step.kind() == PlanStep.Kind.CODE) return code(repo, step.goal());
        String action = step.goal() == null ? "" : step.goal().strip();
        String verb = action.toLowerCase(Locale.ROOT);
        String where = "«" + repo.name() + "»: ";
        if (verb.equals("status")) return where + GitTool.status(repo.path());
        if (verb.equals("pull")) return where + GitTool.pull(repo.path());
        if (verb.equals("push")) return push(repo, confirm);
        if (verb.startsWith("commit")) return commit(repo, explicitMessage(action), following, confirm);
        if (verb.equals("log")) {
            List<String> log = GitTool.log(repo.path(), 8);
            return where + (log.isEmpty() ? "коммитов ещё нет" : "последние коммиты:\n• " + String.join("\n• ", log));
        }
        if (verb.startsWith("branch:")) return where + GitTool.branch(repo.path(), action.substring(7).strip());
        if (verb.equals("discard")) return discard(repo, confirm);
        if (verb.startsWith("run:")) return runCommand(repo, action.substring(4).strip());
        throw new IllegalArgumentException("неизвестная операция git «" + action + "»: умею status, pull, commit, "
                                           + "push, log, branch, discard, run");
    }

    // --- команды проекта и цикл «правка → тесты → исправление» -------------------

    /**
     * Прогнать разрешённую команду проекта («тесты»).
     *
     * <p>Если она не прошла, а в этой задаче агент для кода уже правил этот
     * проект, — правка, скорее всего, и сломала. Тогда агенту для кода уходит
     * исходная задача ВМЕСТЕ с текстом ошибки, и команда прогоняется снова.
     * Попыток немного ({@code code.fixAttempts}, по умолчанию одна): если модель
     * не исправила с первого раза по точному тексту ошибки, вторая попытка
     * чаще ломает что-то ещё, чем чинит, — дальше решает человек.
     *
     * @throws IllegalStateException команда так и не прошла — правки остаются в
     *                               папке, коммита не будет (см. commit)
     */
    private String runCommand(RepoCatalog.Repo repo, String name) throws Exception {
        RepoCommands.Command c = RepoCommands.find(repo, name).orElseThrow(() -> {
            List<String> known = RepoCommands.of(repo).stream().map(RepoCommands.Command::name).toList();
            return new IllegalArgumentException("у «" + repo.name() + "» нет разрешённой команды «" + name + "»; "
                    + (known.isEmpty() ? "команд нет — добавьте строку «" + repo.name() + ": тесты = …» в "
                                         + RepoCommands.file()
                                       : "есть: " + String.join(", ", known)));
        });
        RepoCommands.Run run = RepoCommands.run(repo, c);
        lastCheck.put(repo.path(), run);
        StringBuilder story = new StringBuilder("«" + repo.name() + "»: " + run.verdict());

        int maxFixes = AgentSettings.flag(AUTO_FIX, true) ? AgentSettings.number(FIX_ATTEMPTS, 1, 0, 3) : 0;
        while (!run.ok() && codeTask.containsKey(repo.path()) && fixesUsed.getOrDefault(repo.path(), 0) < maxFixes) {
            fixesUsed.merge(repo.path(), 1, Integer::sum);
            story.append("\n→ правлю по тексту ошибки (попытка ").append(fixesUsed.get(repo.path())).append(")");
            CodeAgent.Result fix;
            try {
                fix = CodeAgent.edit(repo, fixTask(codeTask.get(repo.path()), run), llm);
            } catch (Exception e) {
                story.append(": не вышло — ").append(e.getMessage());
                break;
            }
            if (fix.changed().isEmpty()) {
                story.append(": агент для кода ничего не изменил")
                     .append(fix.said().isEmpty() ? "" : " — " + fix.said());
                break;
            }
            // Сообщение коммита — по исходной правке, а не по исправлению: человек
            // просил «поменяй порт», а не «почини тест». Описание исправления
            // берём, только если исходного описания нет.
            if (lastCodeSummary == null || lastCodeSummary.isBlank()) lastCodeSummary = fix.said();
            story.append(": изменено ").append(String.join(", ", fix.changed()));
            String shown = IdeView.show(repo, fix.changed());
            if (shown != null) story.append(" · ").append(shown);
            run = RepoCommands.run(repo, c);
            lastCheck.put(repo.path(), run);
            story.append("\n").append(run.verdict());
        }
        if (!run.ok()) {
            List<String> key = run.keyLines(6);
            throw new IllegalStateException(story + (key.isEmpty() ? "" : ":\n  " + String.join("\n  ", key))
                    + (codeTask.containsKey(repo.path()) ? "\nПравки остались в папке, коммита не будет." : ""));
        }
        return story.toString();
    }

    /**
     * Задача на исправление: что просили изначально + что сломалось. Вывод
     * команды — последние строки, не больше ~6000 символов: этого хватает на
     * текст ошибки и стек, а промпт не раздувается логом сборки целиком.
     */
    static String fixTask(String original, RepoCommands.Run run) {
        String log = String.join("\n", run.tail());
        if (log.length() > 6000) log = "…\n" + log.substring(log.length() - 6000);
        return "Ты уже сделал правку по задаче: " + original.strip() + "\n"
               + "После неё проверка «" + run.command().name() + "» (" + run.command().line() + ") "
               + (run.timedOut() ? "не уложилась во время" : "завершилась с кодом " + run.exitCode()) + ".\n"
               + "Последние строки вывода:\n" + log + "\n\n"
               + "Исправь так, чтобы проверка прошла, и не отменяй смысл исходной задачи. "
               + "Если ошибка не связана с твоей правкой — ничего не меняй и так и скажи.";
    }

    private static RepoCatalog.Repo repo(String name) {
        return RepoCatalog.find(name).orElseThrow(() -> {
            List<String> known = RepoCatalog.names();
            return new IllegalArgumentException("нет разрешённого репозитория «" + (name == null ? "" : name) + "»; "
                    + (known.isEmpty() ? "список пуст — добавьте строку в " + RepoCatalog.file()
                                       : "есть: " + String.join(", ", known)));
        });
    }

    private String code(RepoCatalog.Repo repo, String task) throws Exception {
        CodeAgent.Result r = CodeAgent.edit(repo, task, llm);
        if (r.changed().isEmpty()) {
            throw new IllegalStateException("агент для кода ничего не изменил"
                                            + (r.said().isEmpty() ? "" : ": " + r.said()));
        }
        lastCodeSummary = r.said();
        codeTask.put(repo.path(), task);
        // Правка поменяла код — прошлый прогон тестов больше ничего о нём не говорит.
        lastCheck.remove(repo.path());
        String shown = IdeView.show(repo, r.changed());
        return "в «" + repo.name() + "» изменено (" + r.engine() + "): " + String.join(", ", r.changed())
               + (r.said().isEmpty() ? "" : " — " + r.said())
               + (shown == null ? "" : " · " + shown);
    }

    /** Сообщение из «commit: Порт 8090»; null — не задано. */
    private static String explicitMessage(String action) {
        int colon = action.indexOf(':');
        String msg = colon < 0 ? "" : action.substring(colon + 1).strip();
        return msg.isEmpty() ? null : msg;
    }

    /**
     * Сообщение коммита: заданное явно, иначе — по описанию правки агента для
     * кода (первая фраза, до 72 символов), иначе — стандартное.
     */
    String messageFor(String explicit) {
        if (explicit != null) return explicit;
        if (lastCodeSummary != null && !lastCodeSummary.isBlank()) {
            String s = lastCodeSummary.strip().split("(?<=[.!?])\\s+|\\R", 2)[0].replaceAll("[.!]+$", "");
            if (!s.isEmpty()) return s.length() <= 72 ? s : s.substring(0, 71) + "…";
        }
        return PlanStep.commitMessage(null);
    }

    private String commit(RepoCatalog.Repo repo, String explicit, List<PlanStep> following,
                          PlanRunner.Confirmer confirm) throws Exception {
        RepoCommands.Run check = lastCheck.get(repo.path());
        if (check != null && !check.ok()) {
            // Страховка на случай, если план дошёл до коммита в обход упавшего
            // шага (перепланирование): сломанный код не коммитим даже с «Да».
            throw new IllegalStateException("коммит не делаю: последняя проверка " + check.verdict()
                                            + ". Правки остались в папке");
        }
        GitTool.Staged staged = GitTool.stageAll(repo.path());
        if (staged.isEmpty()) return "«" + repo.name() + "»: коммитить нечего — изменений нет";
        List<String> secrets = GitTool.secretFiles(staged.files());
        if (!secrets.isEmpty()) {
            GitTool.unstage(repo.path());
            throw new IllegalStateException("коммит отменён: среди изменений файлы с секретами — "
                                            + String.join(", ", secrets) + ". Их нельзя отправлять в git");
        }
        String message = messageFor(explicit);
        boolean pushNext = following.stream().anyMatch(s -> isPushOf(s, repo));
        String q = "Коммит в «" + repo.name() + "»: «" + message + "»\n"
                   + staged.stat() + "\n\n" + staged.preview() + "\n\n"
                   + checkLine(repo, check)
                   + (pushNext ? "Закоммитить и сразу отправить на сервер (push)?" : "Закоммитить?");
        String answer = pushNext ? confirm.choose(q, List.of(YES, ONLY_COMMIT, NO))
                                 : (confirm.confirm(q) ? YES : NO);
        if (!YES.equals(answer) && !ONLY_COMMIT.equals(answer)) {
            GitTool.unstage(repo.path());
            throw new PlanRunner.Declined("человек не подтвердил коммит — изменения остались в папке, не закоммичены");
        }
        String done = GitTool.commit(repo.path(), message);
        if (pushNext) (YES.equals(answer) ? pushApproved : pushDeclined).add(repo.path());
        return "«" + repo.name() + "»: " + done + " (" + staged.files().size() + " файл(ов))"
               + (ONLY_COMMIT.equals(answer) ? ", push не делаю — так выбрано" : "");
    }

    /**
     * Строка о проверке в вопросе о коммите: человек решает «Да» не только по
     * диффу, но и по тому, прошли ли тесты. Если команд проверки у проекта нет,
     * строки нет: «тесты не запускались» там, где их и не бывает, — шум.
     */
    private String checkLine(RepoCatalog.Repo repo, RepoCommands.Run check) {
        if (check != null) return "Проверено: " + check.verdict() + ".\n";
        if (codeTask.containsKey(repo.path()) && RepoCommands.testCommand(repo).isPresent()) {
            return "Тесты после правки не запускались.\n";
        }
        return "";
    }

    private String push(RepoCatalog.Repo repo, PlanRunner.Confirmer confirm) throws Exception {
        if (pushDeclined.contains(repo.path())) return "«" + repo.name() + "»: push пропущен — выбрано «только коммит»";
        List<String> pending = GitTool.pendingPush(repo.path());
        if (!pushApproved.contains(repo.path())) {
            String q = "Отправить на сервер (push) из «" + repo.name() + "», ветка " + GitTool.currentBranch(repo.path())
                       + (pending.isEmpty() ? "?" : ":\n• " + String.join("\n• ", pending) + "\n?");
            if (!confirm.confirm(q)) throw new PlanRunner.Declined("человек не подтвердил push");
        }
        return "«" + repo.name() + "»: " + GitTool.push(repo.path())
               + (pending.isEmpty() ? "" : " (" + pending.size() + " коммит(ов))");
    }

    private static String discard(RepoCatalog.Repo repo, PlanRunner.Confirmer confirm) throws Exception {
        List<String> files = GitTool.discardable(repo.path());
        if (files.isEmpty()) return "«" + repo.name() + "»: откатывать нечего";
        String q = "Откатить несохранённые правки в «" + repo.name() + "»? Эти файлы вернутся к последнему "
                   + "коммиту, правки пропадут:\n• " + String.join("\n• ", files.subList(0, Math.min(15, files.size())))
                   + (files.size() > 15 ? "\n… и ещё " + (files.size() - 15) : "")
                   + "\n(новые файлы, которых нет в git, не трогаю)";
        if (!confirm.confirm(q)) throw new PlanRunner.Declined("человек не подтвердил откат");
        return "«" + repo.name() + "»: " + GitTool.discard(repo.path());
    }

    private static boolean isPushOf(PlanStep s, RepoCatalog.Repo repo) {
        if (s.kind() != PlanStep.Kind.GIT || s.goal() == null || !s.goal().strip().equalsIgnoreCase("push")) return false;
        return RepoCatalog.find(s.app()).map(r -> r.path().equals(repo.path())).orElse(false);
    }

    // --- просьбы, понятные без планировщика ------------------------------------

    private static final String REPO = "(?:\\s+(?:в\\s+|у\\s+)?(?:репозиторий|репозитории|репозитория|репо|проект[ае]?)?\\s*(.+?))?";
    private static final Pattern STATUS = Pattern.compile(
            "^(?:(?:git|гит)\\s+(?:status|статус)|статус\\s+(?:репозитория|репо|проекта))" + REPO + "[.!?]*$");
    private static final Pattern PULL = Pattern.compile(
            "^(?:(?:git|гит)\\s+(?:pull|пул)|обнови\\s+(?:репозиторий|репо)|запуль|спуль)" + REPO + "[.!?]*$");
    private static final Pattern PUSH = Pattern.compile(
            "^(?:(?:git|гит)\\s+(?:push|пуш)|запуш(?:ь|и)|отправь\\s+коммиты)" + REPO + "[.!?]*$");
    private static final Pattern COMMIT_PUSH = Pattern.compile(
            "^(?:закомм?ить|закомить)\\s+и\\s+запуш(?:ь|и)" + REPO + "[.!?]*$");
    private static final Pattern LOG = Pattern.compile(
            "^(?:(?:git|гит)\\s+(?:log|лог)|история(?:\\s+(?:коммитов|репозитория|проекта))?|покажи\\s+коммиты)" + REPO + "[.!?]*$");
    private static final Pattern DISCARD = Pattern.compile(
            "^(?:откати|отмени|сбрось)\\s+(?:все\\s+)?(?:изменения|правки)" + REPO + "[.!?]*$");
    /** «создай ветку feature-x в демо», «переключись на ветку main в демо». */
    private static final Pattern BRANCH = Pattern.compile(
            "^(?:создай|сделай|переключись\\s+на|перейди\\s+на|git\\s+checkout|гит\\s+чекаут)\\s+(?:ветку\\s+)?"
            + "([\\w./-]+)(?:\\s+в\\s+(?:репозитории\\s+|проекте\\s+)?(.+?))?[.!?]*$",
            Pattern.UNICODE_CHARACTER_CLASS);
    /**
     * «правка в демо: поменяй порт на 8090» — с экрана «Проекты» на телефоне.
     * План: правка, коммит (сообщение — по описанию правки), push. Отказ на
     * вопросе о коммите оставляет правку в папке незакоммиченной.
     */
    private static final Pattern EDIT = Pattern.compile("^правка\\s+в\\s+(?:репозитории\\s+|проекте\\s+)?(.+?)\\s*:\\s*(.+)$",
            Pattern.DOTALL);
    /**
     * «прогони тесты в демо», «запусти сборку диплома», «выполни проверку для
     * демо». Совпадает, только если такая команда у этого проекта на самом деле
     * описана (см. preset): иначе «запусти Telegram в фоне» тоже стало бы
     * «командой проекта».
     */
    private static final Pattern RUN = Pattern.compile(
            "^(?:прогони|запусти|выполни|сделай)\\s+(?:команду\\s+)?(.+?)\\s+(?:в|для|у)\\s+"
            + "(?:репозитори[иейя]\\s+|проект[ае]?\\s+)?(.+?)[.!?]*$");

    /**
     * Просьба прямо называет операцию — план без планировщика: «git status демо»,
     * «запушь демо», «закоммить и запушь демо», «история демо», «создай ветку
     * fix в демо», «откати изменения в демо», «правка в демо: …». Так это
     * работает за доли секунды и без обращения к планировщику. Всё сложнее
     * («поменяй порт и запушь» голосом) уходит планировщику.
     *
     * @return готовый план или null — не такая просьба
     */
    public static List<PlanStep> preset(String goal) {
        if (goal == null) return null;
        // (?iu): без «u» регистр не учитывается только у латиницы, и «Фауст» не совпал бы с «фауст».
        String original = goal.strip().replaceFirst("(?iu)^(фауст|faust)[,!\\s]+", "");
        String g = original.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher m;
        if ((m = EDIT.matcher(original)).matches()) {
            String repo = m.group(1).strip(), task = m.group(2).strip();
            List<PlanStep> plan = new ArrayList<>(List.of(PlanStep.code(repo, task)));
            // Есть у проекта тесты — правка без них до коммита не доходит.
            if (AgentSettings.flag(TEST_BEFORE_COMMIT, true)) {
                RepoCatalog.find(repo).flatMap(RepoCommands::testCommand)
                        .ifPresent(c -> plan.add(PlanStep.git(repo, "run: " + c.name())));
            }
            plan.add(PlanStep.git(repo, "commit"));
            plan.add(PlanStep.git(repo, "push"));
            return List.copyOf(plan);
        }
        if ((m = RUN.matcher(g)).matches()) {
            String command = m.group(1).replaceAll("^[«\"']+|[»\"']+$", "").strip();
            String repoName = m.group(2).replaceAll("^[«\"']+|[»\"']+$", "").strip();
            var repo = RepoCatalog.find(repoName);
            if (repo.isPresent() && RepoCommands.find(repo.get(), command).isPresent()) {
                return List.of(PlanStep.git(repoName, "run: " + RepoCommands.find(repo.get(), command).get().name()));
            }
        }
        if ((m = STATUS.matcher(g)).matches()) return List.of(PlanStep.git(name(m), "status"));
        if ((m = PULL.matcher(g)).matches()) return List.of(PlanStep.git(name(m), "pull"));
        if ((m = PUSH.matcher(g)).matches()) return List.of(PlanStep.git(name(m), "push"));
        if ((m = LOG.matcher(g)).matches()) return List.of(PlanStep.git(name(m), "log"));
        if ((m = DISCARD.matcher(g)).matches()) return List.of(PlanStep.git(name(m), "discard"));
        if ((m = COMMIT_PUSH.matcher(g)).matches()) {
            String repo = name(m);
            return List.of(PlanStep.git(repo, "commit"), PlanStep.git(repo, "push"));
        }
        if ((m = BRANCH.matcher(g)).matches()) {
            // Имя ветки — из исходного текста: регистр в именах веток важен.
            Matcher orig = BRANCH.matcher(original.toLowerCase(Locale.ROOT).equals(g) ? original : g);
            String branch = orig.matches() ? orig.group(1) : m.group(1);
            String repo = m.group(2) == null ? "" : m.group(2).replaceAll("^[«\"']+|[»\"']+$", "").strip();
            return List.of(PlanStep.git(repo, "branch: " + branch));
        }
        return null;
    }

    private static String name(Matcher m) {
        String n = m.group(1);
        return n == null ? "" : n.replaceAll("^[«\"']+|[»\"']+$", "").strip();
    }

    /** Названия команд по репозиториям — для подсказки планировщику (командных строк он не видит). */
    public static Map<String, List<String>> repoCommandNames() {
        return RepoCommands.namesByRepo();
    }

    /** Названия репозиториев — для подсказки планировщику (пути он не видит). */
    public static List<String> repoNames() {
        return new ArrayList<>(RepoCatalog.names());
    }
}
