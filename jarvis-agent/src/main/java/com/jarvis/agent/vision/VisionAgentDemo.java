package com.jarvis.agent.vision;

import com.jarvis.agent.AgentConfig;
import com.jarvis.agent.TokenStore;
import com.jarvis.agent.llm.RelayLlmClient;
import com.jarvis.agent.protocol.ResultData;

/**
 * Ручная проверка цикла computer-use агента без телефона (но ЧЕРЕЗ релей —
 * запрос к LLM идёт с прокси-эндпоинта релея, ключ Gemini агент больше не
 * держит сам, см. {@link RelayLlmClient}).
 *
 * Запуск (агент уже должен быть привязан к релею — обычный ./gradlew run
 * хотя бы раз с кодом привязки, чтобы в $HOME/.jarvis-agent появился токен):
 *   ./gradlew runVisionDemo --args="найди на youtube видео про котов и открой первое"
 *
 * (задача runVisionDemo объявлена в build.gradle.kts)
 *
 * Печатает AgentReport: выполнена ли цель, сколько шагов, полный трейс.
 */
public class VisionAgentDemo {

    public static void main(String[] args) {
        String goal = args.length > 0
                ? String.join(" ", args)
                : "найди на youtube видео про котов и открой первое";
        int maxSteps = 8;

        RelayLlmClient relayLlm = new RelayLlmClient(AgentConfig.resolve(new String[0]), new TokenStore());
        GeminiVisionAgent brain = new GeminiVisionAgent(relayLlm);
        if (!brain.isConfigured()) {
            System.err.println("агент не привязан к релею (нет токена в $HOME/.jarvis-agent) — "
                    + "сначала запусти ./gradlew run --args=\"<url> <код привязки>\" и повтори");
            System.exit(1);
        }

        System.out.println("ЦЕЛЬ: " + goal);
        ResultData.AgentReport report = new BrowserAgent(brain).run(goal, maxSteps);

        System.out.println("\n=== AgentReport ===");
        System.out.println("completed  : " + report.completed());
        System.out.println("stepsTaken : " + report.stepsTaken());
        System.out.println("trace      :");
        report.trace().forEach(line -> System.out.println("  " + line));
    }
}
