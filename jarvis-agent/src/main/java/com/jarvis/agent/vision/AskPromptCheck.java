package com.jarvis.agent.vision;

import com.jarvis.agent.AgentConfig;
import com.jarvis.agent.TokenStore;
import com.jarvis.agent.llm.RelayLlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверка промпта вопросов БЕЗ управления экраном: спрашивает ли модель там,
 * где должна, и молчит ли там, где спрашивать незачем.
 *
 * <p>Модели подаётся выдуманное окно — список элементов, какой отдала бы
 * Windows, — и печатается её ответ. Мышь не двигается, окна не открываются,
 * личные чаты не читаются: всё, что видит модель, написано ниже в коде.
 *
 * <p>Запуск (нужен работающий релей с ключом модели и привязанный агент):
 * <pre>
 *   ./gradlew runAskCheck
 *   ./gradlew runAskCheck --args="3"   каждый сценарий по 3 раза (модель не детерминирована)
 * </pre>
 * Итоговая строка — «совпало с ожиданием N из M» — готовая цифра для таблицы.
 */
public class AskPromptCheck {

    /** Сценарий: цель, окно, что ждём от модели (ask или нет). */
    private record Scenario(String name, String goal, String window, List<UiaElement> elements, boolean expectAsk) {}

    public static void main(String[] args) throws Exception {
        int repeats = args.length > 0 ? Integer.parseInt(args[0]) : 1;

        RelayLlmClient relayLlm = new RelayLlmClient(AgentConfig.resolve(new String[0]), new TokenStore());
        GeminiVisionAgent brain = new GeminiVisionAgent(relayLlm);
        if (!brain.isConfigured()) {
            System.err.println("агент не привязан к релею — проверка невозможна");
            System.exit(1);
        }

        int matched = 0, total = 0;
        for (Scenario s : scenarios()) {
            for (int i = 0; i < repeats; i++) {
                long t0 = System.currentTimeMillis();
                AgentAction a = brain.decideNextInWindow(s.goal, s.window, s.elements, List.of());
                long dt = System.currentTimeMillis() - t0;
                boolean ok = a.isAsk() == s.expectAsk;
                if (ok) matched++;
                total++;
                System.out.printf("%s  %-38s ждём %-8s получили %s  (%d мс)%n",
                        ok ? "OK  " : "MISS", s.name, s.expectAsk ? "вопрос" : "действие", a, dt);
            }
        }
        System.out.printf("%nсовпало с ожиданием: %d из %d%n", matched, total);
    }

    // --- сценарии ------------------------------------------------------------

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();

        // 1. Двусмысленность: два подходящих чата — надо спросить, какой.
        list.add(new Scenario("два чата «Лера» — какой?",
                "отправь Лере в телеграме сообщение «буду в 7»",
                "Telegram",
                elements(
                        el("edit", "Поиск", "Лера", true),
                        el("list item", "Лера Иванова, был(а) недавно"),
                        el("list item", "Лера (работа), в сети"),
                        el("list item", "Избранное")),
                true));

        // 2. Необратимое действие: всё готово, осталось нажать «Отправить» — подтвердить.
        list.add(new Scenario("перед отправкой файла — подтвердить",
                "отправь Лере Ивановой в телеграме файл report.pdf",
                "Лера Иванова – Telegram",
                elements(
                        el("text", "Лера Иванова"),
                        el("text", "report.pdf, 240 КБ"),
                        el("edit", "Подпись", "", true),
                        el("button", "Отправить"),
                        el("button", "Отмена")),
                true));

        // 3. Подтверждение уже получено — второй раз спрашивать нельзя.
        list.add(new Scenario("подтверждение уже есть — жать",
                "отправь Лере Ивановой в телеграме файл report.pdf"
                        + "\n\nУТОЧНЕНИЯ ОТ ПОЛЬЗОВАТЕЛЯ (он ответил на твои вопросы — считай это частью цели):"
                        + "\n  - на вопрос «Отправить report.pdf Лере Ивановой?» ответил: «Да»",
                "Лера Иванова – Telegram",
                elements(
                        el("text", "Лера Иванова"),
                        el("text", "report.pdf, 240 КБ"),
                        el("edit", "Подпись", "", true),
                        el("button", "Отправить"),
                        el("button", "Отмена")),
                false));

        // 4. Не хватает данных: какой файл — из цели не понять.
        list.add(new Scenario("какой файл — не сказано",
                "отправь Маше в телеграме файл",
                "Маша – Telegram",
                elements(
                        el("text", "Маша"),
                        el("button", "Прикрепить файл"),
                        el("edit", "Написать сообщение…", "", true)),
                true));

        // 5. Всё однозначно и безопасно — никаких вопросов.
        list.add(new Scenario("блокнот — всё ясно, не спрашивать",
                "напиши в блокноте «список покупок»",
                "Безымянный – Блокнот",
                elements(
                        el("menu item", "Файл"),
                        el("menu item", "Правка"),
                        el("document", "Текстовый редактор", "", true)),
                false));

        // 6. Нужное легко найти самому поиском — спрашивать незачем.
        list.add(new Scenario("можно найти поиском — не спрашивать",
                "открой в проводнике папку Загрузки",
                "Проводник",
                elements(
                        el("edit", "Поиск: Главная"),
                        el("tree item", "Главная"),
                        el("tree item", "Рабочий стол"),
                        el("tree item", "Загрузки"),
                        el("tree item", "Документы")),
                false));

        return list;
    }

    // --- выдуманные элементы окна --------------------------------------------

    private static int nextIndex;

    private static List<UiaElement> elements(UiaElement... els) {
        nextIndex = 0;
        List<UiaElement> out = new ArrayList<>();
        for (UiaElement e : els) {
            // Нумерация — как в настоящем снимке: подряд с 1.
            out.add(new UiaElement(++nextIndex, e.controlType(), e.name(), 0, 0, 100, 30,
                    false, true, e.value(), e.hasFocus()));
        }
        return out;
    }

    private static UiaElement el(String type, String name) {
        return el(type, name, null, false);
    }

    private static UiaElement el(String type, String name, String value, boolean focus) {
        return new UiaElement(0, type, name, 0, 0, 100, 30, false, true, value, focus);
    }
}
