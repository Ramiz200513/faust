package com.jarvis.relay.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Java mirror of the Kotlin `JarvisMessage`. This is the top-level envelope
 * of everything on the WebSocket. Field names and "type" values must match
 * the KMP client byte-for-byte — the protocol tests on the Kotlin side are
 * the reference.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JarvisMessage.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = JarvisMessage.AuthResult.class, name = "auth_result"),
        @JsonSubTypes.Type(value = JarvisMessage.PairRequest.class, name = "pair_request"),
        @JsonSubTypes.Type(value = JarvisMessage.PairResult.class, name = "pair_result"),
        @JsonSubTypes.Type(value = JarvisMessage.CommandRequest.class, name = "command_request"),
        @JsonSubTypes.Type(value = JarvisMessage.CommandResult.class, name = "command_result"),
        @JsonSubTypes.Type(value = JarvisMessage.ScreenFrame.class, name = "screen_frame"),
        @JsonSubTypes.Type(value = JarvisMessage.AgentStep.class, name = "agent_step"),
        @JsonSubTypes.Type(value = JarvisMessage.AgentQuestion.class, name = "agent_question"),
        @JsonSubTypes.Type(value = JarvisMessage.AgentPlan.class, name = "agent_plan"),
        @JsonSubTypes.Type(value = JarvisMessage.Ping.class, name = "ping"),
        @JsonSubTypes.Type(value = JarvisMessage.Pong.class, name = "pong")
})
public sealed interface JarvisMessage permits
        JarvisMessage.Auth, JarvisMessage.AuthResult,
        JarvisMessage.PairRequest, JarvisMessage.PairResult, JarvisMessage.CommandRequest,
        JarvisMessage.CommandResult, JarvisMessage.ScreenFrame, JarvisMessage.AgentStep,
        JarvisMessage.AgentQuestion, JarvisMessage.AgentPlan, JarvisMessage.Ping, JarvisMessage.Pong {

    String requestId();

    record Auth(String requestId, String deviceId, String token,
                DeviceRole role, int protocolVersion) implements JarvisMessage {}

    /**
     * agentOnline — только для телефона: на связи ли сейчас агент того ПК,
     * к которому привязан этот телефон. Для агента всегда null.
     */
    record AuthResult(String requestId, boolean ok, String reason,
                      Boolean agentOnline) implements JarvisMessage {}

    /**
     * Телефон привязывает ПК по одноразовому коду. Шлётся ПЕРВЫМ сообщением
     * вместо Auth. Код должен быть выписан админом именно для этого ПК
     * (POST /api/pairing-codes с полем agentId).
     */
    record PairRequest(String requestId, String code) implements JarvisMessage {}

    /**
     * Ответ на PairRequest. При ok=true: deviceId — id агента (ПК), deviceToken —
     * постоянный токен ТЕЛЕФОНА для этого ПК, deviceName — имя ПК для списка.
     */
    record PairResult(String requestId, boolean ok, String deviceId, String deviceToken,
                      String deviceName, String reason) implements JarvisMessage {}

    record CommandRequest(String requestId, Command command) implements JarvisMessage {}

    record CommandResult(String requestId, CommandStatus status,
                         String message, ResultData data) implements JarvisMessage {}

    record ScreenFrame(String requestId, ImageFormat format, String base64,
                       int width, int height,
                       Integer screenWidth, Integer screenHeight,
                       Integer seq) implements JarvisMessage {}

    record AgentStep(String requestId, int step, String description,
                     String screenshotBase64, ImageFormat screenshotFormat,
                     Integer screenshotWidth, Integer screenshotHeight,
                     Integer targetX, Integer targetY) implements JarvisMessage {}

    /**
     * Вопрос ИИ-агента человеку посреди agent_task (requestId — тот же, что у
     * задачи). Релей его не разбирает, а только пересылает телефонам этого ПК.
     * Поля должны совпадать с Kotlin-версией один в один: релей собирает
     * сообщение заново из этого record, и незнакомое ему поле просто потерялось бы.
     */
    record AgentQuestion(String requestId, String questionId, String question,
                         java.util.List<String> options, Integer timeoutSeconds) implements JarvisMessage {}

    /**
     * План многоэтапной задачи (чек-лист для телефона). Релей его только
     * пересылает, но поля обязан знать все — иначе потеряет их при пересборке.
     */
    record AgentPlan(String requestId, java.util.List<PlanItem> steps, int revision) implements JarvisMessage {}

    /** Одна подзадача плана; status — pending/running/done/failed/skipped. */
    record PlanItem(String title, PlanItemStatus status, String note) {}

    enum PlanItemStatus {
        @com.fasterxml.jackson.annotation.JsonProperty("pending") PENDING,
        @com.fasterxml.jackson.annotation.JsonProperty("running") RUNNING,
        @com.fasterxml.jackson.annotation.JsonProperty("done") DONE,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") FAILED,
        @com.fasterxml.jackson.annotation.JsonProperty("skipped") SKIPPED
    }

    record Ping(String requestId) implements JarvisMessage {}

    record Pong(String requestId) implements JarvisMessage {}
}
