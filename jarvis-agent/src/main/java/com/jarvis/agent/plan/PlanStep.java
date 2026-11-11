package com.jarvis.agent.plan;

/**
 * Одна подзадача плана.
 *
 * <p>Главная идея всего пакета: большую просьбу не отдаём модели целиком.
 * Исполнитель ({@link com.jarvis.agent.vision.BrowserAgent} или
 * {@link com.jarvis.agent.vision.ScreenAgent}) видит одну программу за раз и
 * помнит 8 последних шагов — на задаче из 30 шагов через три программы он
 * терялся. Поэтому план режет её на куски, каждый — в одной программе и
 * такой длины, с которой исполнитель уже справляется.
 *
 * @param kind       где выполняется подзадача
 * @param goal       что сделать — полностью, со всеми именами и названиями: исполнитель
 *                   не видит исходную просьбу целиком (для OPEN_APP не нужен)
 * @param app        какую программу открыть (OPEN_APP), какой скрипт (RUN_SCRIPT) или
 *                   какой репозиторий (GIT, CODE)
 * @param check      что должно быть видно в окне программы, когда подзадача готова
 *                   («в чате Избранное последнее сообщение — файл report.pdf»); null — не проверять
 * @param expectFile какой файл должен появиться («Рабочий стол\курс.txt»); null — не проверять
 * @param waitMinutes только для WAIT: сколько ждать условия, минут (null — по умолчанию из настроек)
 */
public record PlanStep(Kind kind, String goal, String app, String check, String expectFile, Integer waitMinutes) {

    /** Прежний вид — без времени ожидания (оно нужно только WAIT). */
    public PlanStep(Kind kind, String goal, String app, String check, String expectFile) {
        this(kind, goal, app, check, expectFile, null);
    }

    public enum Kind {
        /** На сайте, в браузере агента (Selenium, чистый профиль без логинов). */
        BROWSER,
        /** Внутри программы на ПК (UI Automation, а если разметки нет — по пикселям). */
        SCREEN,
        /**
         * Открыть программу. Выполняется КОДОМ, без модели, тем же путём, что
         * команда open_app с телефона. Это закрывает старую задачу «открыть
         * программу детерминированно, а модели отдать только работу внутри неё».
         */
        OPEN_APP,
        /**
         * Запустить доверенный скрипт пользователя (ScriptCatalog) — поле app
         * хранит его название. Тоже выполняется кодом: модель выбирает только
         * КАКОЙ скрипт, но не пишет и не меняет команду.
         */
        RUN_SCRIPT,
        /**
         * Операция git в разрешённом репозитории (поле app — его название из
         * repos.txt, goal — операция: status, pull, push, «commit: сообщение» или
         * «run: тесты» — разрешённая команда проекта из repo-commands.txt).
         * Выполняется кодом ({@code GitTool}, {@code RepoCommands}); commit и push —
         * только после «Да» человека, который видит дифф.
         */
        GIT,
        /**
         * Правка файлов в разрешённом репозитории внешним агентом для кода
         * ({@code CodeAgent}); app — репозиторий, goal — что изменить. Агенту
         * для кода выдаются только чтение и правка файлов, без терминала.
         */
        CODE,
        /**
         * Подождать, пока выполнится условие: появится файл (expectFile),
         * закроется процесс (app — имя процесса, например «gradle» или «java»)
         * или в окне станет видно нужное (check — смотрит модель, не чаще раза в
         * 15 секунд). goal — что ждём, по-человечески: «пока закончится сборка».
         * Сам шаг ничего на ПК не делает — только смотрит.
         */
        WAIT
    }

    public static PlanStep browser(String goal) { return new PlanStep(Kind.BROWSER, goal, null, null, null); }
    public static PlanStep screen(String goal)  { return new PlanStep(Kind.SCREEN, goal, null, null, null); }
    public static PlanStep openApp(String app)  { return new PlanStep(Kind.OPEN_APP, null, app, null, null); }
    public static PlanStep runScript(String name) { return new PlanStep(Kind.RUN_SCRIPT, null, name, null, null); }
    /** @param action status | pull | push | «commit: сообщение» */
    public static PlanStep git(String repo, String action) { return new PlanStep(Kind.GIT, action, repo, null, null); }
    public static PlanStep code(String repo, String task) { return new PlanStep(Kind.CODE, task, repo, null, null); }
    /** @param process имя процесса, пока он работает — ждём; null — не про процесс */
    public static PlanStep waitFor(String what, String process, String check, String expectFile, Integer minutes) {
        return new PlanStep(Kind.WAIT, what, blankToNull(process), blankToNull(check), blankToNull(expectFile), minutes);
    }

    /** Сообщение коммита из «commit: Порт 8090»; без сообщения — стандартное. */
    public static String commitMessage(String action) {
        int colon = action == null ? -1 : action.indexOf(':');
        String msg = colon < 0 ? "" : action.substring(colon + 1).strip();
        return msg.isEmpty() ? "Изменения с телефона («Фауст»)" : msg;
    }

    /** Та же подзадача, но с проверкой результата. */
    public PlanStep withChecks(String check, String expectFile) {
        return new PlanStep(kind, goal, app, blankToNull(check), blankToNull(expectFile), waitMinutes);
    }

    /** Строка для чек-листа на телефоне. */
    public String title() {
        return switch (kind) {
            case OPEN_APP -> "Открыть " + app;
            case RUN_SCRIPT -> "Запустить скрипт «" + app + "»";
            case CODE -> "Правка в «" + app + "»: " + goal;
            case GIT -> gitTitle();
            case WAIT -> "Дождаться: " + goal;
            default -> goal;
        };
    }

    /** Строка для промпта перепланирования и трейса: «[screen] отправить файл Лере». */
    public String describe() {
        return "[" + kind.name().toLowerCase() + "] " + title();
    }

    private String gitTitle() {
        String a = goal == null ? "" : goal.strip().toLowerCase(java.util.Locale.ROOT);
        String repo = "«" + (app == null || app.isBlank() ? "репозиторий" : app) + "»";
        if (a.equals("status")) return "Статус " + repo;
        if (a.equals("pull")) return "Обновить " + repo + " с сервера (pull)";
        if (a.equals("push")) return "Отправить " + repo + " на сервер (push)";
        if (a.startsWith("commit")) {
            return goal.contains(":") ? "Коммит в " + repo + ": «" + commitMessage(goal) + "»" : "Коммит в " + repo;
        }
        if (a.equals("log")) return "История коммитов " + repo;
        if (a.startsWith("branch:")) return "Ветка " + goal.substring(7).strip() + " в " + repo;
        if (a.equals("discard")) return "Откатить правки в " + repo;
        if (a.startsWith("run:")) return "Прогнать «" + goal.strip().substring(4).strip() + "» в " + repo;
        return "git " + goal + " в " + repo;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
