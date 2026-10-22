package com.jarvis.agent;

import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.Command;
import com.jarvis.agent.protocol.JarvisMessage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Прогон эталонных многоэтапных сценариев БЕЗ телефона — для замеров в
 * научной части (этап 4).
 *
 * <p>Задача идёт ровно тем путём, что и с телефона: {@link CommandExecutor},
 * планировщик, вопросы, журнал runs/*.jsonl. Отличия только два:
 * <ul>
 *   <li>на вопросы отвечает не человек, а правило из сценария (см. {@link #answer});</li>
 *   <li>окно «Разрешить агенту действовать?» на ПК выключено — запуск и так
 *       делает человек, сидящий за этим ПК.</li>
 * </ul>
 *
 * <h2>Правило ответов — главная защита прогона</h2>
 * «Да» на подтверждение ставится, ТОЛЬКО если в вопросе упомянут разрешённый
 * получатель сценария (например, «Избранное» — чат с самим собой). Любой другой
 * вопрос с вариантами «Да/Нет» получает «Нет». Так прогон не может отправить
 * файл живому человеку, даже если модель ошибётся с чатом и спросит об этом.
 *
 * <p>Запуск:
 * <pre>
 *   java -cp "build/install/jarvis-agent/lib/*" com.jarvis.agent.ScenarioRunner rate plan
 *   ... ScenarioRunner rate single      — тот же сценарий без планировщика (базовая линия)
 * </pre>
 */
public class ScenarioRunner {

    /**
     * Эталонный сценарий.
     *
     * @param safeTarget  без этого слова в вопросе подтверждение получает «Нет»; null — подтверждать нечего
     * @param freeAnswer  ответ на вопрос без вариантов «Да/Нет» (null — молчать)
     * @param singleScope режим для прогона без планировщика — как выбрал бы релей
     */
    record Scenario(String id, String goal, String safeTarget, String freeAnswer, AgentScope singleScope) {}

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("rate",
                    "найди на сайте ЦБ РФ официальный курс доллара на сегодня, запиши его в новый документ "
                            + "блокнота и сохрани на рабочий стол как курс.txt",
                    null, null, AgentScope.BROWSER),
            new Scenario("copy",
                    "создай в папке Документы папку ФаустТест и скопируй туда файл faust-test.txt из папки Загрузки",
                    null, null, AgentScope.SCREEN),
            new Scenario("send",
                    "скачай архив по ссылке https://github.com/octocat/Hello-World/archive/refs/heads/master.zip "
                            + "и отправь его в Telegram в Избранное",
                    "Избранн", "в Избранное", AgentScope.BROWSER),
            // Самый длинный: браузер -> Проводник (распаковка) -> Telegram, 5–6 подзадач.
            new Scenario("unzip",
                    "скачай архив по ссылке https://github.com/octocat/Hello-World/archive/refs/heads/master.zip, "
                            + "распакуй его в папку Документы\\ФаустТест и отправь файл README из распакованной "
                            + "папки в Telegram в Избранное",
                    "Избранн", "в Избранное", AgentScope.SCREEN),
            // Доверенный скрипт по имени: без планировщика, но с подтверждением.
            // «Да» — только если в вопросе назван именно этот скрипт.
            new Scenario("script", "запусти скрипт сервер диплома",
                    "сервер диплома", null, AgentScope.SCREEN),
            // git без планировщика: коммит всех изменений и push в тестовый репозиторий
            // (его «сервер» — голый репозиторий на этом же ПК, см. repos.txt).
            new Scenario("gitpush", "закоммить и запушь демо", "«демо»", null, AgentScope.SCREEN),
            // То же, что кнопка экрана «Проекты» на телефоне: правка, коммит и push без планировщика.
            new Scenario("uiedit", "правка в демо: поменяй внешний порт сервиса web в docker-compose на 8090",
                    "«демо»", null, AgentScope.SCREEN),
            // Правка кода агентом для кода + коммит + push: планировщик, CodeAgent, GitTool.
            new Scenario("codepush",
                    "поменяй в репозитории демо внешний порт сервиса web в docker-compose на 8090, "
                            + "закоммить и запушь",
                    "«демо»", null, AgentScope.SCREEN)
    );

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("использование: ScenarioRunner <" + ids() + "> <plan|single>");
            System.exit(2);
        }
        Scenario s = SCENARIOS.stream().filter(x -> x.id().equals(args[0])).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("нет сценария " + args[0] + ", есть: " + ids()));
        boolean plan = "plan".equals(args[1]);

        // Флаги — через системные свойства: agent.properties на этом ПК не трогаем.
        System.setProperty("faust.plan.enabled", String.valueOf(plan));
        System.setProperty("faust.confirmSensitiveCommands", "false");

        CommandExecutor[] executor = new CommandExecutor[1];
        executor[0] = new CommandExecutor(AgentConfig.resolve(new String[0]), new TokenStore(), null,
                msg -> onMessage(msg, s, executor[0]));

        log("СЦЕНАРИЙ " + s.id() + " (" + (plan ? "с планировщиком" : "без планировщика, " + s.singleScope()) + ")");
        log("ЦЕЛЬ: " + s.goal());
        List<JarvisMessage> out = executor[0].execute(UUID.randomUUID().toString(),
                new Command.AgentTask(s.goal(), s.singleScope(), 20));
        for (JarvisMessage m : out) {
            if (m instanceof JarvisMessage.CommandResult r) {
                log("ИТОГ: " + r.status() + " — " + r.message());
            }
        }
        // BrowserAgent держит окно Chrome открытым после задачи (так задумано для
        // телефона). Для прогона — закрываем процесс целиком.
        System.exit(0);
    }

    /** Всё, что агент отправил бы на телефон, — в консоль; вопросы — отвечаем. */
    private static void onMessage(JarvisMessage msg, Scenario s, CommandExecutor executor) {
        switch (msg) {
            case JarvisMessage.AgentStep st -> log("  шаг " + st.step() + ": " + st.description());
            case JarvisMessage.AgentPlan p -> {
                StringBuilder sb = new StringBuilder("  ПЛАН v" + p.revision() + ":");
                for (int i = 0; i < p.steps().size(); i++) {
                    JarvisMessage.PlanItem it = p.steps().get(i);
                    sb.append("\n      ").append(i + 1).append(". [").append(it.status()).append("] ").append(it.title());
                    if (it.note() != null) sb.append(" — ").append(it.note());
                }
                log(sb.toString());
            }
            case JarvisMessage.AgentQuestion q -> {
                String a = answer(q, s);
                log("  ВОПРОС: " + q.question() + " " + q.options() + "  →  ОТВЕТ: " + (a == null ? "(молчу)" : a));
                if (a != null) executor.execute(UUID.randomUUID().toString(), new Command.AnswerQuestion(q.questionId(), a));
            }
            default -> { }
        }
    }

    /** Правило ответа — см. javadoc класса. */
    static String answer(JarvisMessage.AgentQuestion q, Scenario s) {
        boolean yesNo = q.options().stream().anyMatch(o -> o.equalsIgnoreCase("да"));
        if (yesNo) {
            boolean safe = s.safeTarget() != null
                    && q.question().toLowerCase(Locale.ROOT).contains(s.safeTarget().toLowerCase(Locale.ROOT));
            return safe ? "Да" : "Нет";
        }
        // Варианты есть, но это не «Да/Нет» (например, «Повторить / Уже сделано»):
        // прогон не знает правильного ответа — молчит. Молчание агент понимает как
        // «не делать», и это безопасно: ничего не будет повторено вслепую.
        if (!q.options().isEmpty()) return null;
        return s.freeAnswer();
    }

    private static String ids() {
        return String.join("|", SCENARIOS.stream().map(Scenario::id).toList());
    }

    private static void log(String text) {
        System.out.println(LocalTime.now().format(T) + " " + text);
    }
}
