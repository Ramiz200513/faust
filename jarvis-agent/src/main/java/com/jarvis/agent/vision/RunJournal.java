package com.jarvis.agent.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.ResultData;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Журнал каждого запуска computer-use агента — сырьё для подсчёта success
 * rate в дипломе. Один запуск = один файл (имя уже содержит уникальный id,
 * так что дописывать в чужой файл не приходится): $HOME/.jarvis-agent/runs/
 * &lt;дата&gt;-&lt;id&gt;.jsonl, внутри — один JSON-объект (цель, scope, лимиты,
 * итог, полный трейс шагов с их таймингами).
 */
public final class RunJournal {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private RunJournal() {}

    /** Прежняя перегрузка — для запусков, где спросить человека нельзя. */
    public static void write(String goal, AgentScope scope, int maxSteps, ResultData.AgentReport report, long durationMs) {
        write(goal, scope, maxSteps, report, durationMs, 0, 0, 0);
    }

    /**
     * @param questionsAsked    сколько вопросов агент задал человеку
     * @param questionsAnswered на сколько из них человек ответил
     * @param waitedForHumanMs  сколько из durationMs ушло на ожидание ответов —
     *                          чтобы в таблице можно было отделить «время агента»
     *                          от «времени, пока человек думал»
     */
    public static void write(String goal, AgentScope scope, int maxSteps, ResultData.AgentReport report,
                             long durationMs, int questionsAsked, int questionsAnswered, long waitedForHumanMs) {
        write(goal, scope, maxSteps, report, durationMs, questionsAsked, questionsAnswered, waitedForHumanMs,
              java.util.Map.of());
    }

    /**
     * @param extra дополнительные поля журнала — например, сведения о плане
     *              (mode, subtasks, replans, plannerCalls). Так новые режимы
     *              добавляют свои цифры, не плодя перегрузок.
     */
    public static void write(String goal, AgentScope scope, int maxSteps, ResultData.AgentReport report,
                             long durationMs, int questionsAsked, int questionsAnswered, long waitedForHumanMs,
                             java.util.Map<String, Object> extra) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".jarvis-agent", "runs");
            Files.createDirectories(dir);
            String id = UUID.randomUUID().toString().substring(0, 8);
            Path file = dir.resolve(DATE.format(LocalDate.now()) + "-" + id + ".jsonl");

            ObjectNode root = MAPPER.createObjectNode();
            root.put("goal", goal);
            root.put("scope", scope.name());
            root.put("maxSteps", maxSteps);
            root.put("completed", report.completed());
            root.put("stepsTaken", report.stepsTaken());
            root.put("durationMs", durationMs);
            // Две производные величины для раздела «результаты»: пошла ли задача
            // из памяти агента (TrajectoryCache) и во сколько обращений к модели
            // она обошлась. Считаются по трейсу, потому что расширять
            // ResultData.AgentReport значило бы править протокол в трёх местах.
            root.put("fromMemory", fromMemory(report));
            root.put("llmCalls", llmCalls(report));
            root.put("questionsAsked", questionsAsked);
            root.put("questionsAnswered", questionsAnswered);
            root.put("waitedForHumanMs", waitedForHumanMs);
            extra.forEach((k, v) -> root.set(k, MAPPER.valueToTree(v)));
            ArrayNode trace = root.putArray("trace");
            report.trace().forEach(trace::add);

            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(MAPPER.writeValueAsString(root));
                w.newLine();
            }
        } catch (Exception e) {
            // журнал — не критичный путь, не роняем из-за него результат задачи
            System.err.println("не удалось записать журнал запуска: " + e.getMessage());
        }
    }

    /** Задача выполнена повтором сохранённой траектории, без обращений к модели. */
    private static boolean fromMemory(ResultData.AgentReport report) {
        return report.trace() != null
                && report.trace().stream().anyMatch(line -> line.startsWith(TrajectoryCache.REPLAY_MARK));
    }

    /**
     * Сколько раз за запуск спросили модель. -1, если строку в трейсе не нашли (прерванный запуск).
     *
     * <p>Строк с отметкой может быть несколько — по одной на каждую браузерную
     * подзадачу плана, поэтому числа складываются. У обычной задачи строка одна,
     * и результат тот же, что был до планировщика.
     */
    private static int llmCalls(ResultData.AgentReport report) {
        if (report.trace() == null) return -1;
        int sum = 0;
        boolean found = false;
        for (String line : report.trace()) {
            int at = line.indexOf(TrajectoryCache.LLM_CALLS_MARK);
            if (at < 0) continue;
            // Берём только ведущие цифры: у строки повтора после числа идёт
            // хвост «(в первый раз: … )», и parseInt на нём бы сломался.
            String tail = line.substring(at + TrajectoryCache.LLM_CALLS_MARK.length()).trim();
            int end = 0;
            while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
            if (end == 0) continue;
            try {
                sum += Integer.parseInt(tail.substring(0, end));
                found = true;
            } catch (NumberFormatException ignore) {
                // странная строка — пропускаем, остальные всё равно посчитаем
            }
        }
        return found ? sum : -1;
    }
}
