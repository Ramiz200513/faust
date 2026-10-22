package com.jarvis.agent.plan;

import com.jarvis.agent.AgentConfig;
import com.jarvis.agent.TokenStore;
import com.jarvis.agent.llm.RelayLlmClient;

import java.util.List;

/**
 * Проверка промпта планировщика БЕЗ выполнения: модели подаются фразы, на
 * экран ничего не выводится, программы не открываются — только печатается,
 * какой план она составила и совпал ли его вид с ожидаемым.
 *
 * <p>Запуск (нужен работающий релей с ключом модели и привязанный агент):
 * <pre>
 *   ./gradlew runPlanCheck
 *   ./gradlew runPlanCheck --args="3"   каждая фраза по 3 раза
 * </pre>
 */
public class PlanPromptCheck {

    /** Какой ответ планировщика ожидаем. */
    private enum Expect { PLAN_WITH_CONFIRM, PLAN, SINGLE, ASK, GIVE_UP }

    private record Case(String phrase, Expect expect) {}

    private static final List<Case> CASES = List.of(
            // Пример преподавателя: браузер + мессенджер + несколько получателей.
            new Case("скачай с сайта example.com файл и отправь его Лере и Маше в вотсапе", Expect.PLAN_WITH_CONFIRM),
            new Case("найди в загрузках файл отчёт и перешли его в телеграм Пете", Expect.PLAN_WITH_CONFIRM),
            // Непонятно, какой файл, — надо спросить до начала работы.
            new Case("отправь Маше файл", Expect.ASK),
            // Одна программа — план не нужен, прежний путь.
            new Case("найди на ютубе видео про котов и открой первое", Expect.SINGLE),
            // Две подзадачи, но ничего необратимого — без подтверждения.
            new Case("открой блокнот и напиши список покупок", Expect.PLAN),
            // Многоэтапные задачи НЕ про переписку: браузер -> программа, работа с файлами.
            new Case("найди на сайте ЦБ курс доллара на сегодня, запиши его в блокнот и сохрани на рабочий стол как курс.txt",
                    Expect.PLAN),
            new Case("создай в документах папку Диплом и скопируй туда все pdf из загрузок", Expect.PLAN),
            new Case("введи мой пароль от почты на сайте", Expect.GIVE_UP)
    );

    /**
     * Составные рутины: рецепт словами (так его присылает телефон, см.
     * CustomActions.kt recipeGoal) и та же рутина обычной фразой. В обоих
     * случаях в плане должен быть доверенный скрипт — а значит, и подтверждение.
     */
    private static final List<Case> SKILL_CASES = List.of(
            new Case("Навык «утро» — выполни строго по шагам, по порядку:\n"
                     + "1. запусти скрипт сервер диплома\n2. открой Telegram\n3. напиши в Избранное «сервер поднят»",
                    Expect.PLAN_WITH_CONFIRM),
            new Case("подними сервер диплома в докере и напиши в телеграм в Избранное, что сервер поднят",
                    Expect.PLAN_WITH_CONFIRM),
            new Case("подними сервер диплома", Expect.PLAN_WITH_CONFIRM)
    );

    /**
     * @param args [0] сколько раз повторить каждую фразу; [1] «skills» — только составные рутины
     */
    public static void main(String[] args) {
        int repeats = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        List<Case> cases = args.length > 1 && "skills".equals(args[1]) ? SKILL_CASES
                : java.util.stream.Stream.concat(CASES.stream(), SKILL_CASES.stream()).toList();
        RelayLlmClient relayLlm = new RelayLlmClient(AgentConfig.resolve(new String[0]), new TokenStore());
        if (!relayLlm.isConfigured()) {
            System.err.println("агент не привязан к релею — проверка невозможна");
            System.exit(1);
        }
        // Со списком программ этого ПК — как в настоящей работе агента.
        // ...и со списком доверенных скриптов (~/.jarvis-agent/scripts).
        GeminiTaskPlanner planner = new GeminiTaskPlanner(relayLlm, com.jarvis.agent.AppCatalog::installedAppNames,
                com.jarvis.agent.ScriptCatalog::names);

        int matched = 0, total = 0;
        for (Case c : cases) {
            for (int i = 0; i < repeats; i++) {
                long t0 = System.currentTimeMillis();
                PlannerReply reply = planner.plan(c.phrase(), List.of(), null, List.of());
                long dt = System.currentTimeMillis() - t0;
                Expect got = kind(reply);
                boolean ok = got == c.expect();
                if (ok) matched++;
                total++;
                System.out.printf("%n%s  «%s»  ждём %s, получили %s (%d мс)%n",
                        ok ? "OK  " : "MISS", c.phrase(), c.expect(), got, dt);
                print(reply);
            }
        }
        System.out.printf("%nсовпало с ожиданием: %d из %d%n", matched, total);
    }

    private static Expect kind(PlannerReply r) {
        if (r instanceof PlannerReply.Ask) return Expect.ASK;
        if (r instanceof PlannerReply.GiveUp) return Expect.GIVE_UP;
        if (r instanceof PlannerReply.Steps s) {
            // Одна подзадача в браузере или программе — обычная задача (как PlanRunner.isTrivial);
            // один запуск скрипта — уже план: его надо подтвердить.
            PlanStep.Kind k = s.steps().size() == 1 ? s.steps().get(0).kind() : null;
            if (k == PlanStep.Kind.BROWSER || k == PlanStep.Kind.SCREEN) return Expect.SINGLE;
            // Подтверждение считаем так же, как PlanRunner: от модели или собранное кодом.
            return PlanRunner.confirmFor(s).isEmpty() ? Expect.PLAN : Expect.PLAN_WITH_CONFIRM;
        }
        return null; // планировщик недоступен
    }

    private static void print(PlannerReply r) {
        switch (r) {
            case null -> System.out.println("    (нет ответа — планировщик недоступен)");
            case PlannerReply.Ask a -> System.out.println("    вопрос: " + a.question() + " " + a.options());
            case PlannerReply.GiveUp g -> System.out.println("    отказ: " + g.reason());
            case PlannerReply.Steps s -> {
                for (int i = 0; i < s.steps().size(); i++) {
                    PlanStep st = s.steps().get(i);
                    System.out.println("    " + (i + 1) + ") " + st.describe()
                            + (st.expectFile() == null ? "" : "   [файл: " + st.expectFile() + "]")
                            + (st.check() == null ? "" : "   [проверка: " + st.check() + "]"));
                }
                String confirm = PlanRunner.confirmFor(s);
                if (!confirm.isEmpty()) System.out.println("    подтверждение: " + confirm);
            }
        }
    }
}
