plugins {
    application
}

group = "com.jarvis"
version = "0.1.0"

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
tasks.register<JavaExec>("runOverlayDemo") {
    group = "application"
    description = "Показывает все анимации Фауста по сценарию, без релея и модели"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.jarvis.agent.FaustOverlayDemo"
    jvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
