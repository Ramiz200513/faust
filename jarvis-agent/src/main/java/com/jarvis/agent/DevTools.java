package com.jarvis.agent;

import com.jarvis.agent.plan.PlanRunner;
import com.jarvis.agent.plan.PlanStep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        throw new IllegalArgumentException("неизвестная операция git «" + action + "»: умею status, pull, commit, "
                                           + "push, log, branch, discard");
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
        return "в «" + repo.name() + "» изменено (" + r.engine() + "): " + String.join(", ", r.changed())
               + (r.said().isEmpty() ? "" : " — " + r.said());
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
            return List.of(PlanStep.code(repo, task), PlanStep.git(repo, "commit"), PlanStep.git(repo, "push"));
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

    /** Названия репозиториев — для подсказки планировщику (пути он не видит). */
    public static List<String> repoNames() {
        return new ArrayList<>(RepoCatalog.names());
    }
}
