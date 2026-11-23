package com.jarvis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Отличить «научи меня» от «сделай за меня» — только по началу фразы, без модели. */
class MentorRequestTest {

    @Test
    void teachRequests_giveTheTaskItself() {
        assertEquals("включить экранную лупу", MentorRequest.task("Фауст, покажи, как включить экранную лупу"));
        assertEquals("включить экранную лупу", MentorRequest.task("покажи мне как включить экранную лупу?"));
        assertEquals("включить ночной свет", MentorRequest.task("подскажи, как включить ночной свет"));
        assertEquals("отправить файл в телеграм", MentorRequest.task("научи меня отправить файл в телеграм"));
        assertEquals("сделать шрифт крупнее", MentorRequest.task("научи, как сделать шрифт крупнее"));
        assertEquals("поменять обои", MentorRequest.task("объясни мне, как поменять обои"));
        assertEquals("включить лупу", MentorRequest.task("Фауст, пожалуйста, покажи как включить лупу, пожалуйста"));
        assertEquals("сделать скриншот", MentorRequest.task("как мне самой сделать скриншот"));
    }

    @Test
    void bareHowQuestion_isTeaching() {
        // Живой урок 19.11: «как включить экранную лупу» без «покажи» — агент включил лупу сам.
        assertEquals("включить экранную лупу", MentorRequest.task("как включить экранную лупу"));
        assertEquals("включить экранную Лупу", MentorRequest.task("Как включить экранную Лупу?"));
        assertEquals("сделать скриншот", MentorRequest.task("Фауст, как сделать скриншот"));
        assertEquals("найти загрузки", MentorRequest.task("а как мне найти загрузки"));
        assertNull(MentorRequest.task("как дела"));
        assertNull(MentorRequest.task("как тебя зовут"));
        assertNull(MentorRequest.task("как быть"));
    }

    @Test
    void ordinaryCommands_areNotTeaching() {
        assertNull(MentorRequest.task("включи экранную лупу"), "обычная задача — агент делает сам");
        assertNull(MentorRequest.task("покажи рабочий стол"));
        assertNull(MentorRequest.task("покажи коммиты демо"));
        assertNull(MentorRequest.task("покажи папку документы"));
        assertNull(MentorRequest.task("покажи, как дела"));
        assertNull(MentorRequest.task("научи меня"));
        assertNull(MentorRequest.task(null));
    }
}
