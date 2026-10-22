package com.jarvis.relay.security;

import com.jarvis.relay.protocol.AgentScope;
import com.jarvis.relay.protocol.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Запрет опасных целей для ИИ-агента: что обязано быть запрещено и — не менее
 * важно — что обязано остаться разрешённым. Ложный запрет на защите хуже
 * пропущенной редкой формулировки, поэтому вторая половина теста не короче первой.
 */
class ForbiddenGoalsTest {

    @Test
    void dangerousGoals_areDenied() {
        assertEquals("выключение компьютера", ForbiddenGoals.reason("выключи компьютер"));
        assertEquals("выключение компьютера", ForbiddenGoals.reason("Выруби, пожалуйста, мой ноут"));
        assertEquals("выключение компьютера", ForbiddenGoals.reason("shut down the computer"));
        assertEquals("перезагрузка компьютера", ForbiddenGoals.reason("перезагрузи пк"));
        assertEquals("форматирование диска", ForbiddenGoals.reason("отформатируй диск c"));
        assertEquals("массовое удаление", ForbiddenGoals.reason("удали все файлы с рабочего стола"));
        assertEquals("массовое удаление", ForbiddenGoals.reason("сотри всё: документы и фото"));
        assertEquals("перевод денег", ForbiddenGoals.reason("переведи маме пять тысяч"));
        assertEquals("перевод денег", ForbiddenGoals.reason("скинь Пете 2000 тенге"));
        assertEquals("покупка или оплата", ForbiddenGoals.reason("купи мне билет на самолёт"));
        assertEquals("покупка или оплата", ForbiddenGoals.reason("зайди на сайт и оплати интернет"));
    }

    @Test
    void harmlessLookalikes_stayAllowed() {
        // «выключи» и «перезагрузи» без компьютера
        assertNull(ForbiddenGoals.reason("выключи звук в ролике"));
        assertNull(ForbiddenGoals.reason("отключи уведомления в телеграме"));
        assertNull(ForbiddenGoals.reason("перезагрузи страницу и найди кнопку"));
        // удаление одного, названного файла
        assertNull(ForbiddenGoals.reason("удали файл отчёт из загрузок"));
        // «переведи» про язык и «отправь» про файлы и курсы валют
        assertNull(ForbiddenGoals.reason("переведи текст на английский в гугл переводчике"));
        assertNull(ForbiddenGoals.reason("найди курс доллара и отправь его Пете в телеграм"));
        assertNull(ForbiddenGoals.reason("скачай отчёт и отправь его Лере и Маше в вотсапе"));
        // «покупки» — не «купи»
        assertNull(ForbiddenGoals.reason("открой блокнот и напиши список покупок"));
        assertNull(ForbiddenGoals.reason("найди дешёвые авиабилеты в алматы"));
        assertNull(ForbiddenGoals.reason("зайди на озон и добавь в корзину наушники"));
        // навыки и скрипты
        assertNull(ForbiddenGoals.reason("запусти скрипт сервер диплома"));
        assertNull(ForbiddenGoals.reason(""));
        assertNull(ForbiddenGoals.reason(null));
    }

    @Test
    void whitelist_closesTheAgentTaskBypass() {
        CommandWhitelist whitelist = new CommandWhitelist();

        // shutdown запрещён и раньше...
        assertFalse(whitelist.isAllowed(new Command.Shutdown(0)));
        // ...а теперь закрыт и обход через задачу агенту.
        Command bypass = new Command.AgentTask("выключи компьютер", AgentScope.SCREEN, 20);
        assertFalse(whitelist.isAllowed(bypass));
        assertTrue(whitelist.denyReason(bypass).contains("выключение компьютера"));

        assertTrue(whitelist.isAllowed(new Command.AgentTask("открой блокнот и напиши привет", AgentScope.SCREEN, 20)));
        assertTrue(whitelist.isAllowed(new Command.SetVolume(0)), "«выключи звук» — обычная команда");
    }
}
