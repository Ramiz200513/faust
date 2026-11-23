package com.jarvis.relay.llm;

import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * A zero-cost, offline intent parser using simple keyword rules. Good enough
 * to build and demo the whole pipeline before wiring a real LLM. Later you
 * add a GeminiIntentParser implementing the same interface and mark THIS one
 * @Component(... ) off, or use @Primary on the LLM one.
 */
@Component
public class StubIntentParser implements IntentParser {

    /**
     * Начала фраз, которые просят выученный навык или доверенный скрипт на ПК.
     * Их узнаём без модели и всегда одинаково: «запусти скрипт …» иначе легко
     * спутать с «запусти блокнот» (open_app), а «навык …» — вообще не команда,
     * которую модель знает. Что за навык или скрипт — решает уже агент на ПК:
     * только у него есть список выученного.
     */
    private static final String[] SKILL_PREFIXES = {
            "выполни навык ", "запусти навык ", "навык ",
            "выполни скрипт ", "запусти скрипт ", "скрипт "};

    /**
     * Просьбы про git: «git status демо», «запушь демо», «закоммить и запушь».
     * Модели их разбирать незачем — это всегда задача агенту, и только агент
     * знает, какие репозитории разрешены (см. DevTools и RepoCatalog в агенте).
     * Сложное «поменяй порт и запушь» начинается не так — его разберёт модель,
     * и оно тоже станет задачей агенту, просто через планировщик.
     */
    private static final java.util.regex.Pattern GIT = java.util.regex.Pattern.compile(
            "^(?:(?:фауст|faust)[,!\\s]+)?(?:git\\s|гит\\s|запуш[ьи]|закомм?ить|закомить|статус\\s+репозитори|"
            + "обнови\\s+репозитори|правка\\s+в\\s|история\\s+(?:коммитов|репозитори|проект)|покажи\\s+коммиты|"
            + "(?:откати|отмени|сбрось)\\s+(?:все\\s+)?(?:изменения|правки)|(?:создай|сделай)\\s+ветку|"
            + "(?:переключись|перейди)\\s+на\\s+ветку)");

    /**
     * «Продолжи», «доделай задачу» — продолжить оборванный план с места сбоя.
     * Что именно продолжать, знает только агент (ResumeStore). Целиком фраза,
     * а не начало: «продолжи музыку» — это пауза/плей, его разберут правила.
     */
    private static final java.util.regex.Pattern RESUME = java.util.regex.Pattern.compile(
            "^(?:(?:фауст|faust)[,!\\s]+)?(?:продолжи|продолжай|доделай|возобнови)"
            + "(?:\\s+(?:задачу|план|начатое|работу|с\\s+места\\s+(?:сбоя|остановки|ошибки)|где\\s+остановился))?"
            + "(?:\\s+пожалуйста)?[.!]*$");

    /**
     * Режим наставника: «Фауст, покажи, как включить экранную лупу», «подскажи,
     * как сделать скриншот», «научи меня закреплять окна», «объясни, как
     * поменять обои». Человек хочет НАУЧИТЬСЯ: кликать будет он, а агент —
     * показывать стрелку на экране ПК и ждать шага. Различает «научить» и
     * «сделать» уже агент (MentorRequest в jarvis-agent), а релею важно одно —
     * отдать фразу агенту ЦЕЛИКОМ, без моделей: модель охотно превратила бы
     * «покажи, как открыть блокнот» в open_app, и человек ничему бы не научился.
     *
     * Почему именно «покажи/подскажи/объясни + как»: без «как» это обычные
     * команды — «покажи рабочий стол» (quick), «покажи коммиты» (git),
     * «покажи папку документы» (open_app); их это правило не трогает.
     * «Научи» — с «как» и без: «научи меня включать лупу».
     *
     * Список повторяет начало выражения агента (MentorRequest.REQUEST), чтобы
     * релей и агент одинаково понимали, что это просьба научить.
     */
    /**
     * Глагол в неопределённой форме: окончание -ть / -ти / -чь, возможно с -ся / -сь
     * («включить», «найти», «сберечь», «настроиться»). Тот же шаблон — в MentorRequest агента.
     * «Быть», «жить», «стать» исключены: «как быть», «как жить дальше» — разговор, а не урок.
     */
    static final String INFINITIVE = "(?!(?:быть|жить|стать)(?:\\s|$))[а-яё-]+(?:ть|ти|чь)(?:ся|сь)?";

    private static final java.util.regex.Pattern MENTOR = java.util.regex.Pattern.compile(
            "^(?:(?:фауст|faust)[,!.\\s]+)?(?:пожалуйста[,\\s]+)?"
            + "(?:(?:покажи|подскажи|объясни)(?:[,\\s]+мне)?[,\\s]+(?:пожалуйста[,\\s]+)?как(?:\\s+мне)?\\s+"
            + "|научи(?:[,\\s]+меня)?(?:[,\\s]+пожалуйста)?[,\\s]+(?:как\\s+)?"
            // Голый вопрос «как включить экранную лупу?» — тоже просьба научить: живой урок 19.11
            // показал, что человек говорит именно так, а без этого правила агент включал лупу САМ.
            // Обязателен глагол в неопределённой форме сразу после «как» (включить, найти, сберечь,
            // настроиться): «как дела», «как тебя зовут», «как погода» под правило не попадают.
            + "|(?:а\\s+)?как(?:\\s+(?:мне|можно|же))?\\s+(?=" + INFINITIVE + "(?:\\s|$)))"
            + "(.+?)[\\s.!?]*$");

    /**
     * Что после «покажи, как» — не задача на компьютере: «покажи, как дела»,
     * «научи меня» (выражение отдало бы «меня» как задачу), «научи чему-нибудь».
     * Такие фразы оставляем моделям — это болтовня, а не урок.
     */
    private static final java.util.Set<String> NOT_A_LESSON = java.util.Set.of(
            "дела", "ты", "жизнь", "у тебя дела", "меня", "мне",
            "чему-нибудь", "чему нибудь", "чему-то", "всему");

    /** Просьба научить («покажи, как …», «научи меня …»)? Сама цель не проверяется — это работа ForbiddenGoals. */
    static boolean isMentorRequest(String lowerPhrase) {
        java.util.regex.Matcher m = MENTOR.matcher(lowerPhrase);
        if (!m.matches()) return false;
        String task = m.group(1).strip();
        return task.length() >= 3 && !NOT_A_LESSON.contains(task);
    }

    /** Фраза про навык, скрипт, git, «продолжи» или «научи» -> agent_task с ней целиком; иначе null. */
    public static Command skillOrScript(String phrase) {
        if (phrase == null) return null;
        String p = phrase.toLowerCase(Locale.ROOT).strip();
        for (String prefix : SKILL_PREFIXES) {
            if (p.startsWith(prefix) && p.length() > prefix.length()) {
                return new Command.AgentTask(phrase.strip(), AgentScope.SCREEN, 20);
            }
        }
        if (GIT.matcher(p).find()) return new Command.AgentTask(phrase.strip(), AgentScope.SCREEN, 20);
        if (RESUME.matcher(p).matches()) return new Command.AgentTask(phrase.strip(), AgentScope.SCREEN, 20);
        // Фраза уходит агенту как есть (с «покажи, как»): по этим словам агент
        // и поймёт, что надо не нажимать, а показывать. Запрещённые цели
        // («покажи, как выключить компьютер») всё равно остановит белый список
        // (ForbiddenGoals проверяет goal у любого agent_task).
        if (isMentorRequest(p)) return new Command.AgentTask(phrase.strip(), AgentScope.SCREEN, 20);
        return null;
    }

    @Override
    public Command parse(String phrase) {
        String p = phrase.toLowerCase(Locale.ROOT).trim();

        Command skill = skillOrScript(phrase);
        if (skill != null)
            return skill;
        if (p.contains("потише") || p.contains("тише"))
            return new Command.SetVolume(30);
        if (p.contains("погромче") || p.contains("громче"))
            return new Command.SetVolume(80);
        if (p.contains("заблокир"))
            return new Command.LockScreen();
        if (p.startsWith("закрой "))
            return new Command.KillProcess(p.substring(7).trim());
        if (p.startsWith("запусти "))
            return new Command.OpenApp(p.substring(8).trim());
        if (p.contains("процесс"))
            return new Command.ListProcesses();
        if (p.contains("скрин") || p.contains("экран"))
            return new Command.Screenshot();
        if (p.contains("лайк") || p.contains("найди"))
            return new Command.AgentTask(phrase, AgentScope.BROWSER, 15);
        if (p.contains("ютуб") || p.contains("youtube"))
            return new Command.OpenUrl("https://youtube.com");
        if (p.startsWith("открой ") && p.contains("."))
            return new Command.OpenUrl("https://" + p.substring(7).trim());

        return null; // not understood -> relay replies ERROR
    }
}
