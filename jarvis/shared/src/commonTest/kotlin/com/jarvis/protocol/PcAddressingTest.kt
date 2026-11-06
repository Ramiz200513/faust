package com.jarvis.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Разбор «на каком ПК». Главное, что проверяется: ошибка в сторону «не узнал»
 * безопасна (фраза уйдёт на выбранный ПК, как раньше), а в сторону «узнал не
 * тот» — нет. Поэтому здесь столько же проверок на «не трогать», сколько на «узнать».
 */
class PcAddressingTest {

    private val pcs = listOf(
        PcName("pc-work", "Рабочий ПК"),
        PcName("pc-home", "Домашний компьютер"),
        PcName("pc-note", "Ноутбук Рамиза"),
    )

    private fun route(text: String) = PcAddressing.route(text, pcs)

    @Test
    fun onePc_atTheStart_withRussianEndings() {
        assertEquals(PcRoute.One("pc-work", "открой почту"), route("на рабочем открой почту"))
        assertEquals(PcRoute.One("pc-home", "сделай потише"), route("на домашнем компьютере сделай потише"))
        assertEquals(PcRoute.One("pc-note", "открой ютуб"), route("На ноутбуке открой ютуб"))
    }

    @Test
    fun onePc_atTheEnd() {
        assertEquals(PcRoute.One("pc-work", "открой почту"), route("открой почту на рабочем ПК"))
        assertEquals(PcRoute.One("pc-note", "заблокируй экран"), route("заблокируй экран на ноуте Рамиза."))
    }

    @Test
    fun allPcs() {
        assertEquals(PcRoute.All("сделай потише"), route("на всех компьютерах сделай потише"))
        assertEquals(PcRoute.All("открой расписание"), route("открой расписание на всех ПК"))
        assertEquals(PcRoute.All("заблокируй экран"), route("везде заблокируй экран"))
    }

    @Test
    fun transfer_betweenTwoPcs() {
        assertEquals(
            PcRoute.Transfer("отчёт", "pc-home", "pc-work"),
            route("перекинь отчёт с домашнего на рабочий"),
        )
        assertEquals(
            PcRoute.Transfer("отчёт с графиками", "pc-work", "pc-note"),
            route("скопируй файл отчёт с графиками с рабочего ПК на ноутбук"),
        )
    }

    @Test
    fun noAddress_staysOnTheActivePc() {
        // Обычные фразы без ПК — не трогаем.
        assertEquals(PcRoute.Active, route("открой ютуб"))
        assertEquals(PcRoute.Active, route("включи музыку на ютубе"))
        // «Рабочий стол» — папка, а не ПК «Рабочий».
        assertEquals(PcRoute.Active, route("на рабочем столе открой папку проекты"))
        assertEquals(PcRoute.Active, route("сохрани файл на рабочем столе"))
        // Просто «на компьютере» — не называет никакой ПК.
        assertEquals(PcRoute.Active, route("на компьютере открой почту"))
        // «Отправь Васе отчёт» — глагол переноса, но без «с ПК на ПК».
        assertEquals(PcRoute.Active, route("отправь Васе отчёт"))
        // Адрес без самой просьбы — нечего выполнять.
        assertEquals(PcRoute.Active, route("на рабочем"))
    }

    @Test
    fun ambiguousName_isNotGuessed() {
        val twoWork = listOf(PcName("a", "Рабочий ПК"), PcName("b", "Рабочий ноутбук"))
        assertEquals(PcRoute.Active, PcAddressing.route("на рабочем открой почту", twoWork))
        // Но второе слово имени снимает неоднозначность: «ноутбука» у первого ПК нет.
        assertEquals(PcRoute.One("b", "открой почту"), PcAddressing.route("на рабочем ноутбуке открой почту", twoWork))
        // А «на рабочем компьютере» — общее слово, не мешает, но и не выбирает.
        assertEquals(PcRoute.Active, PcAddressing.route("на рабочем компьютере открой почту", twoWork))
    }

    @Test
    fun matchFiles_byWordsAndExactName() {
        val files = listOf("Отчёт_практика.docx", "отчёт-черновик.txt", "Расписание.pdf")
        assertEquals(listOf("Расписание.pdf"), PcAddressing.matchFiles("расписание", files))
        assertEquals(listOf("Отчёт_практика.docx"), PcAddressing.matchFiles("отчёт по практике", files))
        assertEquals(2, PcAddressing.matchFiles("отчёт", files).size, "два отчёта — решает человек")
        assertEquals(emptyList(), PcAddressing.matchFiles("диплом", files))
    }
}
