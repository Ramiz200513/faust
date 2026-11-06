package com.jarvis.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Задача с фото с телефона: разбор ответа модели о фото и цель, которую
 * получает агент. Сам запрос к модели и сохранение файла здесь не нужны —
 * проверяется то, что делает код.
 */
class PhotoTaskTest {

    @Test
    void replyWithTextAndDescription_isSplit() {
        CommandExecutor.PhotoText p = CommandExecutor.parsePhotoReply("""
                Нормализация БД
                1НФ — атомарные значения
                2НФ — нет частичных зависимостей
                ОПИСАНИЕ: фото доски с конспектом про нормальные формы
                """);
        assertEquals("Нормализация БД\n1НФ — атомарные значения\n2НФ — нет частичных зависимостей", p.text());
        assertEquals("фото доски с конспектом про нормальные формы", p.description());
    }

    @Test
    void noTextOnPhoto_isNull_notTheWordsNoText() {
        CommandExecutor.PhotoText p = CommandExecutor.parsePhotoReply("НЕТ ТЕКСТА\nОПИСАНИЕ: кот на подоконнике");
        assertNull(p.text(), "«НЕТ ТЕКСТА» нельзя вставлять в конспект");
        assertEquals("кот на подоконнике", p.description());
        assertNull(CommandExecutor.parsePhotoReply("  ").text());
    }

    @Test
    void veryLongText_isCut() {
        CommandExecutor.PhotoText p = CommandExecutor.parsePhotoReply("а".repeat(5000));
        assertTrue(p.text().length() <= 2001, "длина " + p.text().length());
    }

    @Test
    void goal_hasTheRequest_thePath_andTheText_asFacts() {
        Path saved = Path.of("C:\\Users\\u\\Pictures\\Фауст\\фото-2026-10-29-101500.jpg");
        String goal = CommandExecutor.photoGoal("сохрани в конспект по базам данных и отправь в группу",
                saved, new CommandExecutor.PhotoText("1НФ — атомарные значения", "доска с конспектом"));

        assertTrue(goal.startsWith("сохрани в конспект по базам данных и отправь в группу"), goal);
        assertTrue(goal.contains(saved.toString()), "точный путь — чтобы приложить именно этот файл");
        assertTrue(goal.contains("фото-2026-10-29-101500.jpg"));
        assertTrue(goal.contains("«1НФ — атомарные значения»"));
        assertTrue(goal.contains("буфере обмена"));
    }
}
