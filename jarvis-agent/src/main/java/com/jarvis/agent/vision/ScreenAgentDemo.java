package com.jarvis.agent.vision;

import com.jarvis.agent.AgentConfig;
import com.jarvis.agent.TokenStore;
import com.jarvis.agent.llm.RelayLlmClient;
import com.jarvis.agent.protocol.ResultData;

/**
 * Ручная проверка пиксельного цикла (scope = SCREEN) без телефона (но ЧЕРЕЗ
 * релей, см. {@link VisionAgentDemo}). В отличие от VisionAgentDemo управляет
 * не браузером, а всем экраном — годится, например, для «открой Пуск и
 * запусти калькулятор».
 *
 * Запуск (агент уже должен быть привязан к релею):
 *   ./gradlew runScreenDemo --args="открой Пуск и запусти Калькулятор"
 *
 * (задача runScreenDemo объявлена в build.gradle.kts)
 *
 * Печатает AgentReport: выполнена ли цель, сколько шагов, полный трейс.
 */
public class ScreenAgentDemo {

    public static void main(String[] args) throws Exception {
        String goal = args.length > 0
                ? String.join(" ", args)
                : "открой меню Пуск";
        int maxSteps = 8;

        RelayLlmClient relayLlm = new RelayLlmClient(AgentConfig.resolve(new String[0]), new TokenStore());
        GeminiVisionAgent brain = new GeminiVisionAgent(relayLlm);
        if (!brain.isConfigured()) {
            System.err.println("агент не привязан к релею (нет токена в $HOME/.jarvis-agent) — "
                    + "сначала запусти ./gradlew run --args=\"<url> <код привязки>\" и повтори");
            System.exit(1);
        }

        System.out.println("ЦЕЛЬ: " + goal);
        ResultData.AgentReport report = new ScreenAgent(brain).run(goal, maxSteps);

        System.out.println("\n=== AgentReport ===");
        System.out.println("completed  : " + report.completed());
        System.out.println("stepsTaken : " + report.stepsTaken());
        System.out.println("trace      :");
        report.trace().forEach(line -> System.out.println("  " + line));
    }
}
