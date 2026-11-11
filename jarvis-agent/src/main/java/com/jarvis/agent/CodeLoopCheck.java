package com.jarvis.agent;

import com.jarvis.agent.plan.PlanRunner;
import com.jarvis.agent.plan.PlanStep;

import java.util.List;

/**
 * Живая проверка связки «правка → тесты → коммит → push» без релея и телефона:
 * тот же план, что строит «правка в X: …» с экрана «Проекты», выполняется по
 * шагам настоящими CodeAgent, RepoCommands и GitTool, а время каждого шага
 * печатается — это цифры для защиты.
 *
 * <pre>
 *   ./gradlew runCodeCheck --args="демо|поменяй внешний порт web на 8095"
 *   ./gradlew runCodeCheck --args="демо|поменяй порт на 8095|нет"   на вопрос о коммите ответить «Нет»
 *   ./gradlew runCodeCheck --args="демо"                            только прогнать тесты проекта
 * </pre>
 *
 * <p>Правит только Claude Code: модели через релей тут нет (встроенный
 * редактор на Gemini не вызывается, денег прогон не тратит). Вопросы,
 * которые на телефоне задал бы человеку агент, печатаются вместе с диффом;
 * ответ на них — третий аргумент (по умолчанию «Да»). Работает только с
 * репозиториями из repos.txt, как и сам агент.
 */
public final class CodeLoopCheck {

    private CodeLoopCheck() {}

    public static void main(String[] args) throws Exception {
        String[] a = String.join(" ", args).split("\\|", -1);
        if (a.length == 0 || a[0].isBlank()) {
            System.out.println("нужно: --args=\"репозиторий|что поменять[|ответ на коммит]\" "
                               + "или --args=\"репозиторий\" — только прогнать его тесты");
            return;
        }
        String answer = a.length > 2 && !a[2].isBlank() ? a[2].strip() : DevTools.YES;
        List<PlanStep> plan;
        if (a.length < 2 || a[1].isBlank()) {
            // Только тесты: проверить саму команду из repo-commands.txt, без правки.
            RepoCatalog.Repo repo = RepoCatalog.find(a[0].strip()).orElseThrow(
                    () -> new IllegalArgumentException("нет репозитория «" + a[0].strip() + "» в " + RepoCatalog.file()));
            RepoCommands.Command test = RepoCommands.testCommand(repo).orElseThrow(
                    () -> new IllegalArgumentException("у «" + repo.name() + "» нет команды тестов в " + RepoCommands.file()));
            plan = List.of(PlanStep.git(repo.name(), "run: " + test.name()));
        } else {
            plan = DevTools.preset("правка в " + a[0].strip() + ": " + a[1].strip());
        }
        System.out.println("план: " + plan.stream().map(PlanStep::describe).toList());

        PlanRunner.Confirmer phone = new PlanRunner.Confirmer() {
            @Override
            public boolean confirm(String question) {
                System.out.println("\n[вопрос человеку]\n" + question + "\n[ответ] " + answer + "\n");
                return DevTools.YES.equalsIgnoreCase(answer);
            }

            @Override
            public String choose(String question, List<String> options) {
                System.out.println("\n[вопрос человеку] варианты " + options + "\n" + question + "\n[ответ] " + answer + "\n");
                return options.stream().filter(o -> o.equalsIgnoreCase(answer)).findFirst().orElse(null);
            }
        };

        DevTools dev = new DevTools(null); // null — без встроенного редактора на Gemini
        long total = System.currentTimeMillis();
        for (int i = 0; i < plan.size(); i++) {
            PlanStep step = plan.get(i);
            long t0 = System.currentTimeMillis();
            try {
                String result = dev.run(step, plan.subList(i + 1, plan.size()), phone);
                System.out.printf("✓ %s — %s%n  %s%n", step.title(), RepoCommands.seconds(System.currentTimeMillis() - t0), result);
            } catch (Exception e) {
                System.out.printf("✗ %s — %s%n  %s%n", step.title(), RepoCommands.seconds(System.currentTimeMillis() - t0),
                        e.getMessage());
                break;
            }
        }
        System.out.println("всего: " + RepoCommands.seconds(System.currentTimeMillis() - total));
    }
}
