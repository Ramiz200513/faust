plugins {
    application
}

group = "com.jarvis"
version = "0.1.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Исходники (и старые, и новые) — в UTF-8. Без этого javac на русской Windows
// берёт кодировку windows-1251 и падает на кириллице в комментариях/строках.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    // Jackson for the same JSON protocol the relay uses.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // WebSocket client is java.net.http.WebSocket — built into the JDK, no dep.

    // Selenium: браузерный режим vision-агента. Selenium Manager (встроен с 4.11)
    // сам скачивает нужный chromedriver под установленный Chrome — отдельная
    // зависимость вроде WebDriverManager не нужна.
    implementation("org.seleniumhq.selenium:selenium-java:4.27.0")
    // Тихий логгер, чтобы Selenium не сыпал предупреждением про отсутствие slf4j.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // QR-код в окне «Привязать телефон» — чистая Java, без Android-обёртки.
    implementation("com.google.zxing:core:3.5.3")

    // JNA — вызов функций Windows прямо из Java, без своего C-кода. Нужна
    // оверлеям «Фауст»: сделать окно прозрачным для мыши (клики проходят
    // сквозь него) и спрятать его от снимков экрана (WDA_EXCLUDEFROMCAPTURE).
    // jna-platform уже содержит готовые объявления User32 и тянет за собой jna.
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Тесты. Первые в агенте появились вместе с вопросами человеку: логику
    // «спросил — дождался — запомнил ответ» проверять руками через телефон
    // долго, а сломать её незаметно легко.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // «Как на сервере GitHub»: ./gradlew test -PciLike — английский язык системы (дробные
    // числа через точку) и пустая домашняя папка (нет ~/.jarvis-agent/agent.properties
    // разработчика). Так ловятся тесты, которые проходят только на этом ПК.
    if (project.hasProperty("ciLike")) {
        val home = layout.buildDirectory.dir("tmp/ci-home").get().asFile
        doFirst { home.deleteRecursively(); home.mkdirs() }
        systemProperty("user.home", home.absolutePath)
        jvmArgs("-Duser.language=en", "-Duser.country=US")
    }
    // Тесты не должны трогать настоящую память агента (~/.jarvis-agent/trajectories.json):
    // PlanRunner при опровергнутой проверке «забывает» траекторию.
    systemProperty("faust.trajectoriesFile",
            layout.buildDirectory.file("tmp/test-trajectories.json").get().asFile.absolutePath)
    // То же для навыков и доверенных скриптов: настоящие файлы человека тесты не видят.
    systemProperty("faust.skillsFile",
            layout.buildDirectory.file("tmp/test-skills.json").get().asFile.absolutePath)
    systemProperty("faust.scriptsDir",
            layout.buildDirectory.dir("tmp/test-scripts").get().asFile.absolutePath)
    // Кириллица в сообщениях упавших проверок — читаемой, а не кракозябрами.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

application {
    // `./gradlew run --args="ws://localhost:8080/ws dev-secret-change-me"`
    mainClass = "com.jarvis.agent.AgentMain"
    // Иначе на русской Windows консоль (и наш дублирующий лог-файл) получают
    // не ту кодировку и кириллица превращается в кракозябры.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// --- Программа для Windows -----------------------------------------------------
// Агент как обычная программа: Faust.exe со встроенной Java 21 — на ПК не нужны
// ни Java, ни Gradle, ни Docker (релей, база и модели живут на сервере).
//   ./gradlew packageApp                                     папка build/package/Faust (переносная)
//   ./gradlew packageApp -PrelayUrl=wss://сервер/ws          то же, сразу с адресом сервера
//   ./gradlew packageApp -PinstallerType=exe                 установщик Faust-<версия>.exe (нужен WiX Toolset)
// Адрес сервера уходит в программу как -Dfaust.defaultRelayUrl (см. AgentConfig.defaultRelayUrl):
// человеку при первом запуске ничего не нужно вводить — только привязать телефон.
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Собирает Faust.exe со встроенной Java (jpackage)"
    dependsOn(tasks.named("installDist"))

    val type = (findProperty("installerType") as String?) ?: "app-image"
    val relayUrl = (findProperty("relayUrl") as String?)?.trim().orEmpty()
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val lib = layout.buildDirectory.dir("install/jarvis-agent/lib")
    val dest = layout.buildDirectory.dir("package")
    val jarName = "jarvis-agent-${project.version}.jar"

    // WiX Toolset 3.x (нужен jpackage из JDK 21 для установщика) — из tools/wix314, без
    // установки в систему: архив с github.com/wixtoolset/wix3 распакован туда (папка не в git).
    // PATH подменяется только для этой задачи.
    val wix = file("tools/wix314")
    if (wix.resolve("candle.exe").exists()) {
        environment("PATH", wix.absolutePath + File.pathSeparator + System.getenv("PATH"))
    }

    doFirst {
        if (type != "app-image" && !wix.resolve("candle.exe").exists()) {
            throw GradleException("для установщика нужен WiX Toolset 3.x в jarvis-agent/tools/wix314 " +
                    "(wix314-binaries.zip с github.com/wixtoolset/wix3/releases)")
        }
        // jpackage не перезаписывает готовую папку — убираем прошлую сборку сами.
        delete(dest.get().dir("Faust"))
        val javaOptions = mutableListOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        if (relayUrl.isNotEmpty()) javaOptions += "-Dfaust.defaultRelayUrl=$relayUrl"
        val args = mutableListOf(
            jdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath,
            "--type", type,
            "--name", "Faust",
            "--app-version", project.version.toString(),
            "--vendor", "Faust",
            "--description", "Faust - PC agent",
            "--input", lib.get().asFile.absolutePath,
            "--main-jar", jarName,
            "--main-class", "com.jarvis.agent.AgentMain",
            "--icon", file("src/package/faust.ico").absolutePath,
            "--dest", dest.get().asFile.absolutePath,
            // Встроенная Java без отладочной информации, справки и заголовков C — заметно меньше.
            // Состав модулей не урезаем: без jdk.crypto.ec не заработал бы wss:// к серверу.
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
        )
        javaOptions.forEach { args += listOf("--java-options", it) }
        if (type != "app-image") {
            // Установщик: на текущего пользователя (без прав администратора), ярлыки в
            // «Пуске» и на рабочем столе, выбор папки. upgrade-uuid постоянный — новая
            // версия ставится поверх старой, а не рядом.
            args += listOf("--win-per-user-install", "--win-menu", "--win-menu-group", "Faust",
                    "--win-shortcut", "--win-dir-chooser",
                    "--win-upgrade-uuid", "6f3c1d2e-8a4b-4f7e-9c11-5b2a7d9e0f31")
        }
        commandLine(args)
    }
}

// Все задачи запуска (run, runVisionDemo, runScreenDemo, runOverlayDemo) —
// на той же Java 21, под которую собран код. Задача run получает её от
// application-плагина сама, а самописные JavaExec — нет: без этой строки они
// запускались на Java из JAVA_HOME (17) и падали с UnsupportedClassVersionError.
tasks.withType<JavaExec>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Ручная проверка vision-агента в браузере, в обход релея:
//   ./gradlew runVisionDemo --args="найди на youtube видео про котов и открой первое"
tasks.register<JavaExec>("runVisionDemo") {
    group = "application"
    description = "Прогоняет BrowserAgent по одной цели и печатает AgentReport"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.vision.VisionAgentDemo"
    // JavaExec НЕ наследует applicationDefaultJvmArgs из блока application —
    // без этого трейс с кириллицей печатается кракозябрами.
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Ручная проверка пиксельного (SCREEN) vision-агента, в обход релея:
//   ./gradlew runScreenDemo --args="открой Пуск и запусти Калькулятор"
tasks.register<JavaExec>("runScreenDemo") {
    group = "application"
    description = "Прогоняет ScreenAgent по одной цели и печатает AgentReport"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.vision.ScreenAgentDemo"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Ручная проверка режима UI Automation: печатает список элементов активного окна —
// то, что в этом режиме видит модель вместо картинки экрана.
//   ./gradlew runUiaDemo              снимок через 5 с (успеть переключиться на окно)
//   ./gradlew runUiaDemo --args="0"   снимок сразу
tasks.register<JavaExec>("runUiaDemo") {
    group = "application"
    description = "Печатает элементы активного окна, как их видит агент в режиме UIA"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.vision.UiaSnapshotDemo"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Живая проверка связки «правка → тесты → коммит → push» на репозитории из repos.txt:
// правит настоящий Claude Code, тесты — команда из repo-commands.txt, время каждого шага.
//   ./gradlew runCodeCheck --args="демо|поменяй внешний порт web на 8095"
tasks.register<JavaExec>("runCodeCheck") {
    group = "application"
    description = "Живой прогон правки кода с тестами и коммитом (без релея и телефона)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.CodeLoopCheck"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Проверка промпта вопросов человеку: модели подаются выдуманные окна, мышь не
// двигается. Печатает, спросила ли модель там, где должна.
//   ./gradlew runAskCheck               каждый сценарий по разу
//   ./gradlew runAskCheck --args="3"    по 3 раза — модель не детерминирована
tasks.register<JavaExec>("runAskCheck") {
    group = "application"
    description = "Проверяет, задаёт ли модель вопросы там, где нужно (без управления экраном)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.vision.AskPromptCheck"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Проверка планировщика: модели подаются фразы, ничего не выполняется — только
// печатается план и совпал ли его вид с ожидаемым.
//   ./gradlew runPlanCheck
//   ./gradlew runPlanCheck --args="3"
tasks.register<JavaExec>("runPlanCheck") {
    group = "application"
    description = "Печатает планы, которые составляет модель (без выполнения)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.plan.PlanPromptCheck"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Демонстрация анимаций «Фауст» без релея, телефона и ключа модели: проигрывает
// сценарий задачи (старт -> шаги -> клики -> конец) прямо на этом экране.
//   ./gradlew runOverlayDemo
//   ./gradlew runOverlayDemo --args="record"   <- видно в OBS/Zoom (см. FaustOverlayDemo)
// Режим наставника без релея и модели: стрелка, «не сюда», подсказка, «Готово», голос ПК.
//   ./gradlew runMentorDemo
tasks.register<JavaExec>("runMentorDemo") {
    group = "application"
    description = "Показывает подсказки режима наставника по сценарию, без релея и модели"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.FaustMentorDemo"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.register<JavaExec>("runOverlayDemo") {
    group = "application"
    description = "Показывает все анимации Фауста по сценарию, без релея и модели"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.FaustOverlayDemo"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
