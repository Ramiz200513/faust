package com.jarvis.agent;

import com.jarvis.agent.protocol.AgentScope;
import com.jarvis.agent.protocol.Command;
import com.jarvis.agent.protocol.JarvisMessage;
import com.jarvis.agent.vision.UiaElement;
import com.jarvis.agent.vision.UiaSnapshot;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Самопроверка обучения показом — от записи до повтора, без человека.
 *
 * <p>«Человека» изображает Robot: в Калькуляторе Windows он нажимает
 * C, 7, +, 8, =. DemoRecorder записывает это так же, как записал бы показ
 * мышью, — через ловушки Windows и снимки UI Automation. Затем Калькулятор
 * закрывается, а навык запускается ОБЫЧНЫМ путём агента — командой
 * «навык калькулятор тест» через {@link CommandExecutor}, как с телефона. В
 * конце проверяется дисплей: должно быть 15.
 *
 * <p>Калькулятор выбран намеренно: ничего личного, ничего необратимого, а
 * результат проверяется кодом. Если Калькулятор уже открыт у человека — тест
 * не запускается, чтобы не трогать чужое окно.
 *
 * <p>Запуск (свойства обязательны: навык пишется во временный файл, а
 * искусственный ввод разрешается только этой JVM):
 * <pre>
 *   java -Dfaust.skillsFile=%TEMP%\faust-selftest-skills.json -Dfaust.demo.allowInjected=true
 *        -cp "build/install/jarvis-agent/lib/*" com.jarvis.agent.DemoSelfTest
 * </pre>
 */
public class DemoSelfTest {

    private static final String SKILL = "калькулятор тест";
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Кнопки, которые «показывает» Robot, — русская и английская подписи. */
    private static final String[][] PRESSES = {
            {"Очистить", "Clear"}, {"Семь", "Seven"}, {"Плюс", "Plus"}, {"Восемь", "Eight"}, {"Равно", "Equals"}};

    public static void main(String[] args) throws Exception {
        if (System.getProperty("faust.skillsFile") == null || !Boolean.getBoolean("faust.demo.allowInjected")) {
            System.err.println("нужны -Dfaust.skillsFile=<временный файл> и -Dfaust.demo.allowInjected=true");
            System.exit(2);
        }
        if (calculatorRunning()) {
            System.err.println("Калькулятор уже открыт — тест не запускаю, чтобы не трогать чужое окно");
            System.exit(3);
        }
        UiaSnapshot uia = new UiaSnapshot();
        Robot robot = new Robot();

        // --- 1. Показ ------------------------------------------------------------
        log("1. Открываю Калькулятор и «показываю» C 7 + 8 =");
        new ProcessBuilder("calc.exe").start();
        if (!waitForeground("calculatorapp", 10_000)) fail("Калькулятор не вышел на передний план");
        Thread.sleep(800);

        DemoRecorder recorder = new DemoRecorder((n, d) -> log("   запись: " + n + ") " + d), () -> null);
        recorder.start();
        Thread.sleep(1200); // первые снимки окна
        for (String[] names : PRESSES) {
            UiaElement b = find(uia.captureForegroundWindow(), names);
            if (b == null) fail("не нашёл кнопку " + names[0]);
            robot.mouseMove(b.centerX(), b.centerY());
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(900); // свежий снимок до следующего клика
        }
        DemoRecorder.Recording rec = recorder.stop();
        log("   на дисплее после показа: " + display(uia));
        log("   записано шагов: " + rec.steps().size() + " за " + rec.durationMs() + " мс");
        for (int i = 0; i < rec.descriptions().size(); i++) log("     " + (i + 1) + ") " + rec.descriptions().get(i));
        if (rec.steps().size() < PRESSES.length) fail("записано меньше шагов, чем показано");

        SkillStore.save(new SkillStore.Skill(SKILL, List.of("посчитай тест"), rec.steps(), rec.descriptions(),
                System.currentTimeMillis(), rec.durationMs()));
        closeCalculator();
        Thread.sleep(1500);

        // --- 2. Повтор обычным путём агента -------------------------------------------
        log("2. Запускаю «навык " + SKILL + "» через CommandExecutor, как с телефона");
        System.setProperty("faust.confirmSensitiveCommands", "false");
        CommandExecutor executor = new CommandExecutor(AgentConfig.resolve(new String[0]), new TokenStore(), null,
                DemoSelfTest::onMessage);
        long t0 = System.currentTimeMillis();
        List<JarvisMessage> out = executor.execute(UUID.randomUUID().toString(),
                new Command.AgentTask("навык " + SKILL, AgentScope.SCREEN, 20));
        long dt = System.currentTimeMillis() - t0;
        for (JarvisMessage m : out) {
            if (m instanceof JarvisMessage.CommandResult r) log("   ИТОГ: " + r.status() + " — " + r.message());
        }
        Thread.sleep(500);
        String shown = display(uia);
        log("   на дисплее после повтора: " + shown + " (повтор шёл " + dt + " мс)");
        closeCalculator();

        boolean ok = shown != null && shown.matches(".*\\b15\\b.*");
        log(ok ? "РЕЗУЛЬТАТ: OK — навык, показанный мышью, повторён агентом, на дисплее 15"
               : "РЕЗУЛЬТАТ: ОШИБКА — на дисплее не 15");
        SkillStore.remove(SKILL);
        System.exit(ok ? 0 : 1);
    }

    private static void onMessage(JarvisMessage msg) {
        switch (msg) {
            case JarvisMessage.AgentStep st -> log("   шаг " + st.step() + ": " + st.description());
            case JarvisMessage.AgentPlan p -> {
                StringBuilder sb = new StringBuilder("   ЧЕК-ЛИСТ:");
                for (JarvisMessage.PlanItem it : p.steps()) sb.append(" [").append(it.status()).append("] ").append(it.title()).append(';');
                log(sb.toString());
            }
            default -> { }
        }
    }

    private static UiaElement find(UiaSnapshot.Result w, String[] names) {
        for (UiaElement e : w.elements()) {
            for (String n : names) if (n.equalsIgnoreCase(e.name())) return e;
        }
        return null;
    }

    /** Текст дисплея Калькулятора: «Отображается 15» / «Display is 15». */
    private static String display(UiaSnapshot uia) {
        WindowFocus.bringToFront("Калькулятор");
        for (UiaElement e : uia.captureForegroundWindow().elements()) {
            String n = e.name() == null ? "" : e.name();
            if (n.startsWith("Отображается") || n.startsWith("Display is")) return n;
        }
        return null;
    }

    private static boolean waitForeground(String exe, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (exe.equals(WindowFocus.foregroundExe())) return true;
            WindowFocus.bringToFront("Калькулятор");
            Thread.sleep(300);
        }
        return false;
    }

    /** Через tasklist: путь к exe приложений из Store ProcessHandle видит не всегда. */
    private static boolean calculatorRunning() throws Exception {
        Process p = new ProcessBuilder("tasklist", "/fi", "imagename eq CalculatorApp.exe", "/fo", "csv", "/nh")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.toLowerCase(Locale.ROOT).contains("calculatorapp.exe");
    }

    /** Закрыть Калькулятор, который открыл сам тест (чужой тест не запускает вовсе). */
    private static void closeCalculator() throws Exception {
        new ProcessBuilder("taskkill", "/im", "CalculatorApp.exe", "/f").redirectErrorStream(true).start().waitFor();
    }

    private static void fail(String why) throws Exception {
        log("РЕЗУЛЬТАТ: ОШИБКА — " + why);
        closeCalculator();
        SkillStore.remove(SKILL);
        System.exit(1);
    }

    private static void log(String text) {
        System.out.println(LocalTime.now().format(T) + " " + text);
    }
}
