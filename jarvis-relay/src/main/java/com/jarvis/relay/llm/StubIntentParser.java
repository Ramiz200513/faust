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

    /** Фраза про навык, скрипт, git или «продолжи» -> agent_task с ней целиком; иначе null. */
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
