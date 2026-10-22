package com.jarvis.relay.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Что нельзя поручать ИИ-агенту ни в каком виде — вторая половина белого списка.
 *
 * <h2>Зачем это нужно</h2>
 * Белый список не пропускает команду {@code shutdown}. Но замер 01.10 показал
 * обходной путь: фразу «выключи компьютер» модель превращает не в shutdown, а
 * в {@code agent_task} с целью «выключи компьютер» — команда разрешённая, и
 * агент на ПК дошёл бы мышью до «Пуск → Завершение работы». То же с
 * «отформатируй диск», «переведи маме пять тысяч», «купи билет»: 11 из 11
 * таких просьб Gemini отправил агенту.
 *
 * <p>Поэтому цель задачи проверяется КОДОМ, одинаково для любого разборщика —
 * Gemini, нашей модели, регулярок, рецепта с телефона. Модель может ошибиться
 * или быть обманутой; этот список — нет.
 *
 * <h2>Что запрещено</h2>
 * Выключение и перезагрузка ПК, форматирование, массовое удаление, переводы
 * денег, покупки и оплата. Правила намеренно узкие: «выключи звук»,
 * «перезагрузи страницу», «переведи текст», «удали файл отчёт» — разрешены.
 */
public final class ForbiddenGoals {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    /** «компьютер» в разных видах — объект, без которого «выключи» и «перезагрузи» безобидны. */
    private static final String PC = "(компьютер\\w*|комп|компа|компик|пк|ноутбук\\w*|ноут|ноута|windows|виндовс|винду|"
                                     + "computer|pc|laptop)\\b";
    /** До двух слов между глаголом и объектом: «выключи, пожалуйста, мой компьютер». */
    private static final String SEP = "[\\s,:;—–-]+";
    private static final String GAP = SEP + "(?:\\S+" + SEP + "){0,2}";

    /** До трёх слов — для сумм: «переведи маме на карту пять тысяч». */
    private static final String GAP3 = SEP + "(?:\\S+" + SEP + "){0,3}";
    private static final String MONEY = "(тенге|рубл\\w*|доллар\\w*|евро|тг|руб|тысяч\\w*)";

    private record Rule(String reason, Pattern pattern) {}

    private static final List<Rule> RULES = new ArrayList<>();

    private static void rule(String reason, String regex) {
        RULES.add(new Rule(reason, Pattern.compile(regex, FLAGS)));
    }

    static {
        rule("выключение компьютера",
                "\\b(выключ\\w*|выруб\\w*|отключ\\w*|заверш\\w*\\s+работу|shut\\s*down|power\\s*off|turn\\s+off)" + GAP + PC);
        rule("перезагрузка компьютера",
                "\\b(перезагруз\\w*|перезапус\\w*|ребутн\\w*|reboot|restart)" + GAP + PC);
        rule("форматирование диска",
                "\\b(отформатир\\w*|форматир\\w*|format)" + GAP + "(диск\\w*|флешк\\w*|раздел\\w*|накопител\\w*|drive|disk)\\b");
        rule("массовое удаление",
                "\\b(удал\\w*|сотри|стер\\w*|очист\\w*|снеси|delete|wipe|erase)" + GAP
                + "(все|весь|всю|all)" + GAP + "(файл\\w*|данн\\w*|папк\\w*|документ\\w*|фото\\w*|фотк\\w*|диск\\w*|"
                + "files|data|folders)\\b");
        // «Переведи/перечисли» + любая сумма или валюта. «Переведи текст» сюда не попадает.
        rule("перевод денег",
                "\\b(перевед\\w*|перевест\\w*|перечисл\\w*)" + GAP3
                + "(деньг\\w*|денег|на\\s+карту|на\\s+счет|" + MONEY + ")\\b");
        // «Отправь/скинь» — только с деньгами прямо: «отправь Пете курс доллара» — обычная задача.
        rule("перевод денег",
                "\\b(отправ\\w*|скинь|кинь|закинь)" + GAP3 + "(деньг\\w*|денег|\\d+\\s*" + MONEY + ")\\b");
        rule("покупка или оплата",
                "\\b(купи|закажи|оплати|заплати|оформи\\s+заказ|buy|purchase)\\b");
    }

    private ForbiddenGoals() {}

    /**
     * @return почему цель запрещена («выключение компьютера»), или null — запрета нет
     */
    public static String reason(String goal) {
        if (goal == null || goal.isBlank()) return null;
        // ё -> е: в правилах только «е», чтобы «всё» и «все», «счёт» и «счет» совпадали.
        String g = goal.replace('ё', 'е').replace('Ё', 'Е');
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(g).find()) return rule.reason();
        }
        return null;
    }
}
