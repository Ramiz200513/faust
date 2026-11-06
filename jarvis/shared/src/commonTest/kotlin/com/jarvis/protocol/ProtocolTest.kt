package com.jarvis.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip tests: every message must serialize to JSON and back unchanged.
 * These JSON shapes are the contract the Java relay must match exactly.
 */
class ProtocolTest {

    private inline fun <reified T> roundTrip(value: T): String {
        val json = JarvisJson.encodeToString(value)
        val back = JarvisJson.decodeFromString<T>(json)
        assertEquals(value, back, "round-trip changed the value")
        return json
    }

    @Test
    fun commandRequest_setVolume_roundTrips() {
        val msg: JarvisMessage = CommandRequest("r1", SetVolume(30))
        val json = roundTrip(msg)
        assertTrue(json.contains("\"type\":\"command_request\""), json)
        assertTrue(json.contains("\"type\":\"set_volume\""), json)
        assertTrue(json.contains("\"level\":30"), json)
    }

    @Test
    fun naturalLanguage_roundTrips() {
        val msg: JarvisMessage = CommandRequest("r2", NaturalLanguage("сделай потише"))
        roundTrip(msg)
    }

    @Test
    fun agentTask_defaultsToBrowserScope() {
        val task = AgentTask(goal = "поставь лайк")
        assertEquals(AgentScope.BROWSER, task.scope)
        roundTrip<JarvisMessage>(CommandRequest("r3", task))
    }

    @Test
    fun commandResult_withProcessList_roundTrips() {
        val msg: JarvisMessage = CommandResult(
            requestId = "r4",
            status = CommandStatus.OK,
            data = ProcessList(listOf(ProcessInfo(1234, "chrome.exe"))),
        )
        roundTrip(msg)
    }

    @Test
    fun dataObjectCommand_roundTrips() {
        roundTrip<JarvisMessage>(CommandRequest("r5", Screenshot))
    }

    @Test
    fun pairRequest_roundTrips() {
        val msg: JarvisMessage = PairRequest(requestId = "p1", code = "482931")
        val json = roundTrip(msg)
        assertTrue(json.contains("\"type\":\"pair_request\""), json)
    }

    @Test
    fun pairResult_success_roundTrips() {
        val msg: JarvisMessage = PairResult(
            requestId = "p1",
            ok = true,
            deviceId = "pc-42",
            deviceToken = "secret-token",
            deviceName = "Рабочий ПК",
        )
        roundTrip(msg)
    }

    @Test
    fun pairResult_failure_roundTrips() {
        val msg: JarvisMessage = PairResult(requestId = "p1", ok = false, reason = "код истёк")
        roundTrip(msg)
    }

    @Test
    fun authResult_withAgentOnlineFlag_roundTrips() {
        val msg: JarvisMessage = AuthResult(requestId = "a1", ok = true, agentOnline = false)
        val json = roundTrip(msg)
        assertTrue(json.contains("\"agentOnline\":false"), json)
    }

    @Test
    fun authResult_withoutAgentOnline_defaultsToNull() {
        val msg = AuthResult(requestId = "a1", ok = true)
        assertEquals(null, msg.agentOnline)
        roundTrip<JarvisMessage>(msg)
    }

    @Test
    fun agentQuestion_withOptions_roundTrips() {
        val msg: JarvisMessage = AgentQuestion(
            requestId = "t1",
            questionId = "q1",
            question = "Отправить report.pdf Лере?",
            options = listOf("Да", "Нет"),
            timeoutSeconds = 120,
        )
        val json = roundTrip(msg)
        assertTrue(json.contains("\"type\":\"agent_question\""), json)
        assertTrue(json.contains("\"options\":[\"Да\",\"Нет\"]"), json)
    }

    @Test
    fun agentQuestion_withoutOptions_defaultsToEmptyList() {
        // Так выглядит вопрос «ответь своими словами» от агента, который не
        // прислал ни options, ни timeoutSeconds, — телефон не должен падать.
        val json = """{"type":"agent_question","requestId":"t1","questionId":"q2","question":"Какой файл?"}"""
        val msg = JarvisJson.decodeFromString<JarvisMessage>(json) as AgentQuestion
        assertEquals(emptyList(), msg.options)
        assertEquals(null, msg.timeoutSeconds)
    }

    @Test
    fun answerQuestion_roundTrips() {
        val msg: JarvisMessage = CommandRequest("a2", AnswerQuestion(questionId = "q1", answer = "Да"))
        val json = roundTrip(msg)
        assertTrue(json.contains("\"type\":\"answer_question\""), json)
    }

    @Test
    fun agentPlan_roundTrips_withLowercaseStatuses() {
        val msg: JarvisMessage = AgentPlan(
            requestId = "t1",
            steps = listOf(
                PlanItem("Скачать файл", PlanItemStatus.DONE, note = "report.pdf"),
                PlanItem("Открыть WhatsApp", PlanItemStatus.RUNNING),
                PlanItem("Отправить файл Лере"),
            ),
            revision = 2,
        )
        val json = roundTrip(msg)
        assertTrue(json.contains("\"type\":\"agent_plan\""), json)
        // Java-зеркала (релей, агент) пишут статусы строчными — формат должен совпадать.
        assertTrue(json.contains("\"status\":\"running\""), json)
    }

    @Test
    fun agentPlan_fromJava_withNullNote_decodes() {
        // Jackson на стороне агента пишет note:null явно — телефон не должен на этом падать.
        val json = """{"type":"agent_plan","requestId":"t1","revision":1,
            "steps":[{"title":"Открыть WhatsApp","status":"pending","note":null}]}"""
        val msg = JarvisJson.decodeFromString<JarvisMessage>(json) as AgentPlan
        assertEquals(PlanItemStatus.PENDING, msg.steps.single().status)
    }

    @Test
    fun setDisplayOption_roundTrips() {
        val json = roundTrip<JarvisMessage>(CommandRequest("d1", SetDisplayOption(option = "xray", enabled = false)))
        assertTrue(json.contains("\"type\":\"set_display_option\""), json)
        assertTrue(json.contains("\"enabled\":false"), json)
    }

    @Test
    fun sequence_ofSimpleCommands_roundTrips_inTheSameJsonAsJava() {
        val cmd = Sequence(listOf(SetVolume(30), QuickAction("next_track"), OpenUrl("https://youtube.com")))
        val json = roundTrip<JarvisMessage>(CommandRequest("s1", cmd))
        // Тот же вид, что читает Jackson в релее и агенте: тип у каждой команды внутри списка.
        assertTrue(json.contains("\"type\":\"sequence\""), json)
        assertTrue(json.contains("{\"type\":\"quick_action\",\"action\":\"next_track\""), json)
        assertTrue(CommandWhitelist.DEFAULT.isAllowed(cmd))
    }

    @Test
    fun repoList_fromJavaAgent_isReadable() {
        // Ровно так ответ на dev_repos пишет Jackson в агенте (record -> поля по именам, null как null).
        val json = """{"type":"command_result","requestId":"r1","status":"ok","message":"проектов: 1",
            "data":{"type":"repo_list","repos":[{"name":"демо","branch":"main","changed":["M docker-compose.yml"],
            "ahead":1,"behind":0,"lastCommit":"e8bea24 Порт 8090 · 2 мин назад","hasRemote":true,"error":null}]}}"""
        val result = JarvisJson.decodeFromString<JarvisMessage>(json) as CommandResult
        val repo = (result.data as RepoList).repos.single()
        assertEquals("демо", repo.name)
        assertEquals(1, repo.ahead)
        assertEquals(listOf("M docker-compose.yml"), repo.changed)
        assertTrue(CommandWhitelist.DEFAULT.isAllowed(DevRepos))
        assertTrue(roundTrip<JarvisMessage>(CommandRequest("r2", DevRepos)).contains("\"type\":\"dev_repos\""))
    }

    @Test
    fun correctionPhotoAndReport_commands_roundTrip() {
        assertTrue(roundTrip<JarvisMessage>(CommandRequest("c1", CorrectTask("нет, не Пете, а Васе")))
            .contains("\"type\":\"correct_task\""))
        val photo = roundTrip<JarvisMessage>(CommandRequest("p1", PhotoTask("сохрани в конспект", "AAAA")))
        assertTrue(photo.contains("\"type\":\"photo_task\"") && photo.contains("\"imageBase64\":\"AAAA\""), photo)
        // asGif по умолчанию тоже уходит в JSON (encodeDefaults) — Java-сторона его ждёт.
        val get = roundTrip<JarvisMessage>(CommandRequest("g1", GetTaskReport("task-1")))
        assertTrue(get.contains("\"taskId\":\"task-1\"") && get.contains("\"asGif\":false"), get)
        for (c in listOf(CorrectTask("x"), PhotoTask("x", "y"), GetTaskReport("t"))) {
            assertTrue(CommandWhitelist.DEFAULT.isAllowed(c), c.name)
        }
    }

    @Test
    fun taskReport_fromJavaAgent_isReadable() {
        // Ровно так ответ на get_task_report пишет Jackson в агенте.
        val json = """{"type":"command_result","requestId":"r1","status":"ok","message":"видео-отчёт: 2 кадров",
            "data":{"type":"task_report","taskId":"task-1","goal":"отправь отчёт Васе","completed":true,
            "frames":[{"step":0,"caption":"Задача: отправь отчёт Васе","base64":"AA=="},
                      {"step":-1,"caption":"Итог: цель достигнута.","base64":"BB=="}]}}"""
        val report = (JarvisJson.decodeFromString<JarvisMessage>(json) as CommandResult).data as TaskReport
        assertEquals(listOf(0, -1), report.frames.map { it.step })
        assertTrue(report.completed)
    }

    @Test
    fun whitelist_blocksShutdown_allowsSetVolume() {
        val wl = CommandWhitelist.DEFAULT
        assertTrue(wl.isAllowed(SetVolume(10)))
        assertFalse(wl.isAllowed(Shutdown(0)))
        assertEquals(null, wl.check(Shutdown(0)))
    }
}
