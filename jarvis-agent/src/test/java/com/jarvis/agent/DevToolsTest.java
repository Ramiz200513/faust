package com.jarvis.agent;

import com.jarvis.agent.plan.PlanRunner;
import com.jarvis.agent.plan.PlanStep;
import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.CancelSignal;
import com.jarvis.agent.vision.Clarifications;
import com.jarvis.agent.vision.UserAsker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * git в плане задачи — на НАСТОЯЩЕМ git во временной папке: «сервер» — голый
 * репозиторий рядом, push уходит в него. Проверяется то, за что отвечает код:
 * что именно спрашивается у человека, что делается без его «Да» (ничего) и что
 * не уходит никогда (секреты).
 */
class DevToolsTest {

    @TempDir
    Path tmp;
    private Path work;
    private Path remote;

    @BeforeEach
    void repo() throws IOException {
        System.setProperty("faust.ask.enabled", "true");
        System.setProperty("faust.ask.maxPerTask", "10");
        remote = tmp.resolve("remote.git");
        work = tmp.resolve("демо");
        run(tmp, "git", "init", "-q", "--bare", "-b", "main", remote.toString());
        run(tmp, "git", "clone", "-q", remote.toString(), work.toString());
        run(work, "git", "config", "user.name", "Тест");
        run(work, "git", "config", "user.email", "test@example.com");
        Files.writeString(work.resolve("docker-compose.yml"), "ports:\n  - \"8080:80\"\n");
        run(work, "git", "add", "-A");
        run(work, "git", "commit", "-q", "-m", "начало");
        run(work, "git", "push", "-q", "-u", "origin", "main");

        Path list = tmp.resolve("repos.txt");
        Files.writeString(list, "# тест\nдемо = " + work + "\nпропавший = " + tmp.resolve("нет такой") + "\n",
                StandardCharsets.UTF_8);
        System.setProperty("faust.reposFile", list.toString());
        // Свой файл команд (пустой, пока тест не запишет): настоящий файл
        // пользователя не должен влиять на тесты.
        commands = tmp.resolve("repo-commands.txt");
        System.setProperty("faust.repoCommandsFile", commands.toString());
        // На ПК разработчика может быть открыта Android Studio — тесты не должны
        // открывать в ней свои временные файлы.
        System.setProperty("faust.code.showInIde", "off");
        CodeAgent.resetPause();
    }

    private Path commands;

    @AfterEach
    void clean() {
        System.clearProperty("faust.reposFile");
        System.clearProperty("faust.repoCommandsFile");
        System.clearProperty("faust.code.showInIde");
        System.clearProperty("faust.code.engine");
        System.clearProperty("faust.code.command");
        CodeAgent.resetPause();
    }

    // --- список репозиториев ------------------------------------------------------

    @Test
    void catalog_knowsOnlyExistingRepos_andFindsByPartOfName() {
        assertEquals(List.of("демо"), RepoCatalog.names(), "папка без .git в список не попадает");
        assertEquals(work.toAbsolutePath().normalize(), RepoCatalog.find("Демо").orElseThrow().path());
        assertTrue(RepoCatalog.find("").isPresent(), "единственный репозиторий — и без названия понятно какой");
        assertTrue(RepoCatalog.find("чужой").isEmpty());
    }

    // --- просьбы без планировщика ------------------------------------------------

    @Test
    void preset_recognizesDirectGitRequests() {
        assertEquals(List.of(PlanStep.git("демо", "status")), DevTools.preset("git status демо"));
        assertEquals(List.of(PlanStep.git("демо", "push")), DevTools.preset("Фауст, запушь демо"));
        assertEquals(List.of(PlanStep.git("демо", "push")), DevTools.preset("запушь проект демо"));
        assertEquals(List.of(PlanStep.git("демо", "pull")), DevTools.preset("гит пул демо"));
        assertEquals(List.of(PlanStep.git("", "push")), DevTools.preset("запушь"));
        List<PlanStep> both = DevTools.preset("закоммить и запушь демо");
        assertEquals(2, both.size());
        assertEquals("push", both.get(1).goal());
        assertNull(DevTools.preset("открой блокнот"));
        assertNull(DevTools.preset("поменяй порт в демо и запушь"), "сложная просьба — дело планировщика");
    }

    // --- выполнение по плану -----------------------------------------------------

    /** «Телефон»: отвечает по очереди и запоминает вопросы. */
    static final class Phone implements UserAsker {
        final List<String> answers;
        final List<String> asked = new ArrayList<>();

        Phone(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public String ask(String question, List<String> options, CancelSignal cancel) {
            asked.add(question);
            return answers.isEmpty() ? null : answers.remove(0);
        }
    }

    private ResultData.AgentReport runPreset(Phone phone, PlanStep... steps) {
        return runPreset(phone, new DevTools(), steps);
    }

    private ResultData.AgentReport runPreset(Phone phone, DevTools dev, PlanStep... steps) {
        PlanRunner.SubtaskExecutor noScreen = (step, deadline) -> {
            throw new AssertionError("в git-плане экран не нужен: " + step);
        };
        PlanRunner runner = new PlanRunner(null, noScreen, name -> name, (items, rev) -> { },
                new Clarifications(phone), CancelSignal.NONE,
                new PlanRunner.Limits(8, 2, 3, Duration.ofMinutes(3)))
                .withDev(dev)
                .withPreset(List.of(steps));
        PlanRunner.Outcome out = runner.run("git", AgentScope.SCREEN, Instant.now().plus(Duration.ofMinutes(5)));
        return assertInstanceOf(PlanRunner.Outcome.Finished.class, out).report();
    }

    @Test
    void commitAndPush_askOnce_withDiff_andReachServer() throws IOException {
        Files.writeString(work.resolve("docker-compose.yml"), "ports:\n  - \"8090:80\"\n");
        Phone phone = new Phone("Да");

        ResultData.AgentReport r = runPreset(phone,
                PlanStep.git("демо", "commit: Порт 8090"), PlanStep.git("демо", "push"));

        assertTrue(r.completed(), String.join("\n", r.trace()));
        assertEquals(1, phone.asked.size(), "коммит и следующий push — один вопрос");
        String q = phone.asked.get(0);
        assertTrue(q.contains("docker-compose.yml") && q.contains("+  - \"8090:80\""), "в вопросе виден дифф: " + q);
        assertTrue(q.contains("push"), q);
        assertEquals("Порт 8090", git(remote, "log", "-1", "--format=%s").strip(), "коммит дошёл до сервера");
    }

    @Test
    void declinedCommit_changesNothing_andStopsPlan() throws IOException {
        Files.writeString(work.resolve("docker-compose.yml"), "ports:\n  - \"9999:80\"\n");
        String headBefore = git(work, "rev-parse", "HEAD");

        ResultData.AgentReport r = runPreset(new Phone("Нет"),
                PlanStep.git("демо", "commit: Порт 9999"), PlanStep.git("демо", "push"));

        assertFalse(r.completed());
        assertEquals(headBefore, git(work, "rev-parse", "HEAD"), "без «Да» коммита нет");
        assertEquals("", git(work, "diff", "--cached", "--name-only").strip(), "индекс возвращён как был");
        assertTrue(Files.readString(work.resolve("docker-compose.yml")).contains("9999"), "правка на диске цела");
    }

    @Test
    void silence_isNo() throws IOException {
        Files.writeString(work.resolve("new.txt"), "x");
        String headBefore = git(work, "rev-parse", "HEAD");

        ResultData.AgentReport r = runPreset(new Phone(/* молчит */), PlanStep.git("демо", "commit"));

        assertFalse(r.completed());
        assertEquals(headBefore, git(work, "rev-parse", "HEAD"));
    }

    @Test
    void secrets_neverCommitted_evenWithYes() throws IOException {
        Files.writeString(work.resolve(".env"), "API_KEY=секрет");
        Files.writeString(work.resolve("app.txt"), "код");
        Phone phone = new Phone("Да");

        ResultData.AgentReport r = runPreset(phone, PlanStep.git("демо", "commit: с секретом"));

        assertFalse(r.completed());
        assertTrue(phone.asked.isEmpty(), "про коммит с секретом даже не спрашиваем");
        assertTrue(String.join("\n", r.trace()).contains(".env"), String.join("\n", r.trace()));
        assertEquals("", git(work, "diff", "--cached", "--name-only").strip());
    }

    @Test
    void secretPatterns() {
        assertEquals(List.of(".env", "conf/.env.prod", "keys/id_rsa", "cert.pem"),
                GitTool.secretFiles(List.of(".env", "conf/.env.prod", ".env.example", "keys/id_rsa", "cert.pem",
                        "src/env.java", "README.md")));
    }

    @Test
    void pushAlone_asksWithCommitList() throws IOException {
        Files.writeString(work.resolve("a.txt"), "1");
        run(work, "git", "add", "-A");
        run(work, "git", "commit", "-q", "-m", "локальный коммит");
        Phone phone = new Phone("да");

        ResultData.AgentReport r = runPreset(phone, PlanStep.git("демо", "push"));

        assertTrue(r.completed(), String.join("\n", r.trace()));
        assertTrue(phone.asked.get(0).contains("локальный коммит"), phone.asked.get(0));
        assertEquals("локальный коммит", git(remote, "log", "-1", "--format=%s").strip());
    }

    @Test
    void status_and_unknownRepo() {
        ResultData.AgentReport ok = runPreset(new Phone(), PlanStep.git("демо", "status"));
        assertTrue(ok.completed());
        assertTrue(String.join("\n", ok.trace()).contains("ветка main"), String.join("\n", ok.trace()));

        ResultData.AgentReport bad = runPreset(new Phone(), PlanStep.git("чужой", "push"));
        assertFalse(bad.completed());
        assertTrue(String.join("\n", bad.trace()).contains("есть: демо"), String.join("\n", bad.trace()));
    }

    @Test
    void branchHeader_inPlainRussian() {
        assertEquals("ветка main, совпадает с origin/main", GitTool.describeBranch("main...origin/main"));
        assertEquals("ветка main, относительно origin/main: не отправлено коммитов — 2, не скачано — 1",
                GitTool.describeBranch("main...origin/main [ahead 2, behind 1]"));
        assertEquals("ветка dev (не связана с сервером)", GitTool.describeBranch("dev"));
    }

    // --- правка кода встроенным редактором (модель подделана) ---------------------

    /** «Модель», которая меняет порт и описывает это одной фразой. */
    private static final GeminiCodeEditor.Llm PORT_EDITOR = prompt -> {
        assertTrue(prompt.contains("===== docker-compose.yml ====="), "маленький проект уходит модели целиком");
        return """
               ```json
               {"edits":[{"file":"docker-compose.yml","search":"  - \\"8080:80\\"","replace":"  - \\"8090:80\\""}],
                "summary":"Внешний порт web изменён на 8090. Остальное не трогал."}
               ```""";
    };

    @Test
    void editFromPhone_commitMessageFromSummary_andPush() throws IOException {
        System.setProperty("faust.code.engine", "gemini");
        try {
            Phone phone = new Phone("Да");
            List<PlanStep> plan = DevTools.preset("правка в демо: поменяй порт на 8090");
            assertEquals(3, plan.size());

            ResultData.AgentReport r = runPreset(phone, new DevTools(PORT_EDITOR), plan.toArray(PlanStep[]::new));

            assertTrue(r.completed(), String.join(" | ", r.trace()));
            assertTrue(Files.readString(work.resolve("docker-compose.yml")).contains("8090:80"));
            assertEquals(1, phone.asked.size(), "один вопрос — коммит и push вместе");
            assertTrue(phone.asked.get(0).contains("+  - \"8090:80\""), "в вопросе дифф правки");
            assertEquals("Внешний порт web изменён на 8090", git(remote, "log", "-1", "--format=%s").strip(),
                    "сообщение коммита — первая фраза описания правки");
        } finally {
            System.clearProperty("faust.code.engine");
        }
    }

    @Test
    void onlyCommit_skipsPush() throws IOException {
        Files.writeString(work.resolve("a.txt"), "локально");
        String remoteBefore = git(remote, "rev-parse", "HEAD");

        ResultData.AgentReport r = runPreset(new Phone("Только коммит"),
                PlanStep.git("демо", "commit: только локально"), PlanStep.git("демо", "push"));

        assertTrue(r.completed(), String.join(" | ", r.trace()));
        assertEquals("только локально", git(work, "log", "-1", "--format=%s").strip());
        assertEquals(remoteBefore, git(remote, "rev-parse", "HEAD"), "на сервер ничего не ушло");
    }

    @Test
    void editor_appliesOnlyUniqueFragments_andKeepsLineEndings() {
        assertEquals("a\r\nX\r\nc\r\n", GeminiCodeEditor.applyOne("a\r\nb\r\nc\r\n", "b\n", "X\n", "f"),
                "CRLF файла сохраняется, хотя модель пишет просто \\n");
        assertThrows(IllegalStateException.class, () -> GeminiCodeEditor.applyOne("a b a", "a", "x", "f"),
                "фрагмент дважды — не угадываем");
        assertThrows(IllegalStateException.class, () -> GeminiCodeEditor.applyOne("abc", "zzz", "x", "f"));
        assertThrows(IllegalStateException.class, () -> GeminiCodeEditor.safePath(work, "../чужой.txt"));
        assertThrows(IllegalStateException.class, () -> GeminiCodeEditor.safePath(work, ".git/config"));
        assertThrows(IllegalStateException.class, () -> GeminiCodeEditor.safePath(work, "conf/.env"));
    }

    @Test
    void editor_failingEdit_leavesProjectUntouched() throws IOException {
        System.setProperty("faust.code.engine", "gemini");
        try {
            GeminiCodeEditor.Llm half = prompt -> """
                    {"edits":[{"file":"docker-compose.yml","search":"8080","replace":"8090"},
                              {"file":"docker-compose.yml","search":"нет такого","replace":"x"}],"summary":"s"}""";
            String before = Files.readString(work.resolve("docker-compose.yml"));

            ResultData.AgentReport r = runPreset(new Phone("Да"), new DevTools(half), PlanStep.code("демо", "порт"));

            assertFalse(r.completed());
            assertEquals(before, Files.readString(work.resolve("docker-compose.yml")), "полправки не записывается");
        } finally {
            System.clearProperty("faust.code.engine");
        }
    }

    @Test
    void log_branch_discard() throws IOException {
        ResultData.AgentReport log = runPreset(new Phone(), DevTools.preset("история демо").toArray(PlanStep[]::new));
        assertTrue(String.join(" | ", log.trace()).contains("начало (Тест"), String.join(" | ", log.trace()));

        ResultData.AgentReport br = runPreset(new Phone(),
                DevTools.preset("создай ветку feature/port в демо").toArray(PlanStep[]::new));
        assertTrue(br.completed(), String.join(" | ", br.trace()));
        assertEquals("feature/port", git(work, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertFalse(runPreset(new Phone(), PlanStep.git("демо", "branch: плохое имя..")).completed());

        Files.writeString(work.resolve("docker-compose.yml"), "испорчено");
        Files.writeString(work.resolve("новый.txt"), "не трогать");
        assertFalse(runPreset(new Phone("Нет"), PlanStep.git("демо", "discard")).completed());
        assertEquals("испорчено", Files.readString(work.resolve("docker-compose.yml")), "без «Да» не откатывается");
        Phone yes = new Phone("Да");
        assertTrue(runPreset(yes, DevTools.preset("откати изменения в демо").toArray(PlanStep[]::new)).completed());
        assertTrue(yes.asked.get(0).contains("docker-compose.yml"), "в вопросе — какие файлы пропадут");
        assertTrue(Files.readString(work.resolve("docker-compose.yml")).contains("8080"));
        assertTrue(Files.exists(work.resolve("новый.txt")), "новые файлы откат не удаляет");
    }

    @Test
    void repoInfo_forPhoneScreen() throws IOException {
        Files.writeString(work.resolve("b.txt"), "новый");
        ResultData.RepoInfo info = GitTool.info("демо", work);
        assertEquals("main", info.branch());
        assertEquals(List.of("?? b.txt"), info.changed());
        assertTrue(info.hasRemote());
        assertTrue(info.lastCommit().contains("начало"), info.lastCommit());
        assertNull(info.error());
        // Те же имена полей, что читает телефон (RepoInfo в :shared, тест repoList_fromJavaAgent_isReadable).
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new ResultData.RepoList(List.of(info)));
        assertTrue(json.startsWith("{\"type\":\"repo_list\",\"repos\":[{\"name\":\"демо\",\"branch\":\"main\""), json);
        assertTrue(json.contains("\"hasRemote\":true") && json.contains("\"ahead\":0"), json);
    }

    // --- команды проекта и цикл «правка → тесты → исправление» ------------------

    /**
     * Проверка проекта — настоящий .cmd в репозитории: падает, если в
     * docker-compose.yml есть слово BROKEN. Так «сломать» и «починить» проект
     * может поддельная модель, а прогон идёт настоящим процессом.
     */
    private void addCheck() throws IOException {
        Files.writeString(work.resolve("check.cmd"), """
                @echo off
                findstr /C:"BROKEN" docker-compose.yml >nul
                if %errorlevel%==0 (echo FAIL: BROKEN in docker-compose.yml & exit /b 1)
                echo OK
                exit /b 0
                """.replace("\n", "\r\n"));
        run(work, "git", "add", "-A");
        run(work, "git", "commit", "-q", "-m", "проверка");
        run(work, "git", "push", "-q");
        Files.writeString(commands, "# тест\nдемо: тесты = check.cmd\nдемо: сборка = \"C:\\Program Files\\нет\\x.exe\" a\n",
                StandardCharsets.UTF_8);
    }

    /**
     * «Модель»: правка порта ломает проект (BROKEN), а задача на исправление —
     * с текстом ошибки — чинит. Запоминает, о чём её просили.
     */
    private static final class BreakThenFix implements GeminiCodeEditor.Llm {
        final List<String> prompts = new ArrayList<>();
        final boolean canFix;

        BreakThenFix(boolean canFix) {
            this.canFix = canFix;
        }

        @Override
        public String generate(String prompt) {
            prompts.add(prompt);
            // Не по «FAIL: BROKEN»: эти слова есть и в самом check.cmd, а маленький
            // проект уходит модели целиком. Задачу на исправление выдаёт её начало.
            if (prompt.contains("Последние строки вывода")) {
                return canFix
                        ? """
                          {"edits":[{"file":"docker-compose.yml","search":"8090:80\\" # BROKEN","replace":"8090:80\\""}],
                           "summary":"Убрал пометку, из-за которой падала проверка."}"""
                        : """
                          {"edits":[{"file":"docker-compose.yml","search":"ports:","replace":"ports: # попытка"}],
                           "summary":"Попробовал другое."}""";
            }
            return """
                   {"edits":[{"file":"docker-compose.yml","search":"8080:80\\"","replace":"8090:80\\" # BROKEN"}],
                    "summary":"Внешний порт web изменён на 8090."}""";
        }
    }

    @Test
    void commands_parsedFromFile_foundByNameOrCase() throws IOException {
        addCheck();
        RepoCatalog.Repo demo = RepoCatalog.find("демо").orElseThrow();
        assertEquals(List.of("тесты", "сборка"), RepoCommands.of(demo).stream().map(RepoCommands.Command::name).toList());
        assertEquals(List.of("C:\\Program Files\\нет\\x.exe", "a"), RepoCommands.find(demo, "сборку").orElseThrow().argv(),
                "кавычки — один аргумент; двоеточие в пути не путается с «репозиторий:»; «сборкУ» = «сборкА»");
        assertEquals("тесты", RepoCommands.find(demo, "тест").orElseThrow().name());
        assertEquals("тесты", RepoCommands.testCommand(demo).orElseThrow().name());
        assertTrue(RepoCommands.find(demo, "деплой").isEmpty());
        assertEquals(java.util.Map.of("демо", List.of("тесты", "сборка")), RepoCommands.namesByRepo());

        List<String> cmd = RepoCommands.resolve(work, List.of("check.cmd"));
        assertEquals(List.of("cmd.exe", "/c", work.resolve("check.cmd").toAbsolutePath().normalize().toString()), cmd,
                "программа из папки проекта — по полному пути");
        Files.writeString(work.resolve("gradlew"), "#!/bin/sh");
        Files.writeString(work.resolve("gradlew.bat"), "@echo off");
        assertTrue(RepoCommands.resolve(work, List.of("gradlew", "test")).get(2).endsWith("gradlew.bat"),
                "рядом лежит gradlew для Linux — берём .bat");
    }

    @Test
    void preset_runCommand_onlyIfDescribed_andEditGetsTestsBeforeCommit() throws IOException {
        assertEquals(3, DevTools.preset("правка в демо: порт 8090").size(), "без описанных тестов — как раньше");
        assertNull(DevTools.preset("прогони тесты в демо"), "команды нет — не перехватываем просьбу");
        addCheck();
        assertEquals(List.of(PlanStep.git("демо", "run: тесты")), DevTools.preset("Фауст, прогони тесты в демо"));
        assertEquals(List.of(PlanStep.git("демо", "run: сборка")), DevTools.preset("запусти сборку для проекта демо"));
        assertNull(DevTools.preset("запусти телеграм в фоне"));
        assertEquals(List.of(PlanStep.code("демо", "порт 8090"), PlanStep.git("демо", "run: тесты"),
                PlanStep.git("демо", "commit"), PlanStep.git("демо", "push")), DevTools.preset("правка в демо: порт 8090"));
        assertEquals("Прогнать «тесты» в «демо»", PlanStep.git("демо", "run: тесты").title());
    }

    @Test
    void failingTests_fixedByCodeAgent_thenCommitWithCheckResult() throws IOException {
        addCheck();
        System.setProperty("faust.code.engine", "gemini");
        BreakThenFix llm = new BreakThenFix(true);
        Phone phone = new Phone("Да");

        ResultData.AgentReport r = runPreset(phone, new DevTools(llm),
                DevTools.preset("правка в демо: поменяй порт на 8090").toArray(PlanStep[]::new));

        String trace = String.join("\n", r.trace());
        assertTrue(r.completed(), trace);
        assertEquals(2, llm.prompts.size(), "правка + одно исправление");
        assertTrue(llm.prompts.get(1).contains("поменяй порт на 8090") && llm.prompts.get(1).contains("FAIL: BROKEN"),
                "исправлению уходят и исходная задача, и текст ошибки");
        assertTrue(trace.contains("«тесты» не прошли") && trace.contains("«тесты» прошли"), trace);
        assertEquals(1, phone.asked.size());
        assertTrue(phone.asked.get(0).contains("Проверено: «тесты» прошли"), phone.asked.get(0));
        assertEquals("Внешний порт web изменён на 8090", git(remote, "log", "-1", "--format=%s").strip(),
                "сообщение — по исходной правке, а не по исправлению");
        assertFalse(Files.readString(work.resolve("docker-compose.yml")).contains("BROKEN"));
    }

    @Test
    void testsStillFailing_noCommit_andChangesStayInFolder() throws IOException {
        addCheck();
        System.setProperty("faust.code.engine", "gemini");
        BreakThenFix llm = new BreakThenFix(false);
        Phone phone = new Phone("Да");
        String headBefore = git(work, "rev-parse", "HEAD");

        ResultData.AgentReport r = runPreset(phone, new DevTools(llm),
                DevTools.preset("правка в демо: поменяй порт на 8090").toArray(PlanStep[]::new));

        String trace = String.join("\n", r.trace());
        assertFalse(r.completed(), trace);
        assertEquals(2, llm.prompts.size(), "попытка исправить — одна (code.fixAttempts=1)");
        assertTrue(trace.contains("FAIL: BROKEN"), "человек видит, что именно упало: " + trace);
        assertTrue(phone.asked.isEmpty(), "про коммит сломанного кода даже не спрашиваем");
        assertEquals(headBefore, git(work, "rev-parse", "HEAD"));
        assertTrue(Files.readString(work.resolve("docker-compose.yml")).contains("8090"), "правка не потеряна");
    }

    @Test
    void testsWithoutEdit_noFixAttempt() throws IOException {
        addCheck();
        Files.writeString(work.resolve("docker-compose.yml"), "BROKEN руками");
        BreakThenFix llm = new BreakThenFix(true);

        ResultData.AgentReport r = runPreset(new Phone(), new DevTools(llm), PlanStep.git("демо", "run: тесты"));

        assertFalse(r.completed());
        assertTrue(llm.prompts.isEmpty(), "проект сломал не агент — чинить чужое без спроса нельзя");
        assertTrue(String.join("\n", r.trace()).contains("не прошли, код 1"), String.join("\n", r.trace()));

        ResultData.AgentReport unknown = runPreset(new Phone(), PlanStep.git("демо", "run: деплой"));
        assertTrue(String.join("\n", unknown.trace()).contains("есть: тесты, сборка"), String.join("\n", unknown.trace()));
    }

    @Test
    void claudeLimit_orCrash_fallsBackToBuiltInEditor() throws IOException {
        // Поддельный «claude»: дочитывает задачу и отвечает, что лимит исчерпан.
        Path fake = tmp.resolve("claude-limit.cmd");
        Files.writeString(fake, "@more >nul\r\n@echo {\"type\":\"result\",\"is_error\":true,"
                                + "\"result\":\"Claude AI usage limit reached\"}\r\n");
        System.setProperty("faust.code.command", fake.toString());
        ResultData.AgentReport r = runPreset(new Phone(), new DevTools(PORT_EDITOR), PlanStep.code("демо", "порт 8090"));
        String trace = String.join("\n", r.trace());
        assertTrue(r.completed(), trace);
        assertTrue(trace.contains("встроенный редактор, т.к. у Claude Code кончился лимит"), trace);

        // Отказ по существу («опасно») — НЕ повод отдать задачу другой модели.
        CodeAgent.resetPause();
        run(work, "git", "checkout", "-q", "--", ".");
        Path refuse = tmp.resolve("claude-refuse.cmd");
        Files.writeString(refuse, "@more >nul\r\n@echo {\"type\":\"result\",\"is_error\":true,"
                                  + "\"result\":\"Refusing: task looks destructive\"}\r\n");
        System.setProperty("faust.code.command", refuse.toString());
        BreakThenFix spy = new BreakThenFix(true);
        ResultData.AgentReport no = runPreset(new Phone(), new DevTools(spy), PlanStep.code("демо", "удали всё"));
        assertFalse(no.completed());
        assertTrue(spy.prompts.isEmpty(), "запасной редактор не вызывался");
    }

    @Test
    void claudeReplyClassification() {
        assertEquals(CodeAgent.Trouble.LIMIT, CodeAgent.trouble(CodeAgent.parse(
                "{\"is_error\":true,\"result\":\"You've hit your limit · resets 5pm\"}")));
        assertEquals(CodeAgent.Trouble.NOT_LOGGED_IN, CodeAgent.trouble(CodeAgent.parse(
                "{\"is_error\":true,\"result\":\"Invalid API key · Please run /login\"}")));
        // Настоящий ответ claude -p на этом ПК 11.11 (403 oauth_not_allowed_for_organization):
        // в нём есть «API key», но это не «не вошёл».
        assertEquals(CodeAgent.Trouble.NO_SUBSCRIPTION, CodeAgent.trouble(CodeAgent.parse(
                "{\"is_error\":true,\"api_error_status\":403,\"result\":\"Claude Code can't use your Claude subscription,"
                + " usually because a payment is past due · Pay or renew at https://claude.ai/settings/billing"
                + " (if you just paid, try again shortly), or use an Anthropic API key instead\"}")));
        assertEquals(CodeAgent.Trouble.BROKEN, CodeAgent.trouble(CodeAgent.parse("Segmentation fault")));
        assertEquals(CodeAgent.Trouble.REFUSED, CodeAgent.trouble(CodeAgent.parse(
                "{\"is_error\":true,\"result\":\"не нашёл, что менять\"}")));
    }

    // --- показ правки в среде разработки (без запуска самих сред) -----------------

    @Test
    void ide_choice_kind_andLine() throws IOException {
        var all = List.of(IdeView.Ide.VS_CODE, IdeView.Ide.ANDROID_STUDIO, IdeView.Ide.PYCHARM);
        assertEquals(IdeView.Ide.ANDROID_STUDIO, IdeView.choose(IdeView.ProjectKind.ANDROID, all).orElseThrow());
        assertEquals(IdeView.Ide.PYCHARM, IdeView.choose(IdeView.ProjectKind.PYTHON, all).orElseThrow());
        assertEquals(IdeView.Ide.VS_CODE, IdeView.choose(IdeView.ProjectKind.OTHER, all).orElseThrow());
        assertEquals(IdeView.Ide.ANDROID_STUDIO,
                IdeView.choose(IdeView.ProjectKind.OTHER, List.of(IdeView.Ide.ANDROID_STUDIO)).orElseThrow(),
                "открыта только Android Studio — покажем в ней");
        assertTrue(IdeView.choose(IdeView.ProjectKind.JVM, List.of()).isEmpty());

        assertEquals(IdeView.ProjectKind.OTHER, IdeView.kind(work));
        Files.createDirectories(work.resolve("app"));
        Files.writeString(work.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }");
        assertEquals(IdeView.ProjectKind.ANDROID, IdeView.kind(work));

        assertEquals(12, IdeView.firstChangedLine("diff --git a/x b/x\n@@ -12 +12 @@\n-a\n+b\n@@ -40,0 +41,2 @@\n"));
        assertEquals(8, IdeView.firstChangedLine("@@ -8,2 +7,0 @@\n-a\n-b\n"), "только удаление — строка после");
        assertEquals(1, IdeView.firstChangedLine(""));
        Files.writeString(work.resolve("docker-compose.yml"), "ports:\n  - \"8090:80\"\n");
        assertEquals(2, IdeView.firstChangedLine(RepoCatalog.find("демо").orElseThrow(), "docker-compose.yml"));

        assertEquals(List.of("studio64.exe", "--line", "2", "C:\\p\\a.kt"),
                IdeView.Ide.ANDROID_STUDIO.openArgs("studio64.exe", Path.of("C:\\p\\a.kt"), 2));
        assertEquals(List.of("Code.exe", "-g", "C:\\p\\a.kt:2"),
                IdeView.Ide.VS_CODE.openArgs("Code.exe", Path.of("C:\\p\\a.kt"), 2));
    }

    // --- вспомогательное -----------------------------------------------------------

    private static String git(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        return exec(dir, cmd);
    }

    private static void run(Path dir, String... cmd) throws IOException {
        exec(dir, List.of(cmd));
    }

    private static String exec(Path dir, List<String> cmd) throws IOException {
        Files.createDirectories(dir);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        try {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + ": " + out);
            return out;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
