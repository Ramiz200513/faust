package com.jarvis.protocol

/** Привязанный ПК, к которому можно обратиться по имени: id и имя, как его видит человек. */
data class PcName(val id: String, val name: String)

/** На каком ПК (или ПК) выполнить фразу — итог [PcAddressing.route]. */
sealed interface PcRoute {
    /** ПК в фразе не назван — как раньше, на выбранный в приложении. */
    data object Active : PcRoute

    /** «На рабочем открой почту» — один названный ПК; [rest] — просьба без адреса. */
    data class One(val pcId: String, val rest: String) : PcRoute

    /** «На всех компьютерах сделай потише» — все привязанные ПК разом. */
    data class All(val rest: String) : PcRoute

    /** «Перекинь отчёт с домашнего на рабочий» — файл с одного ПК на другой. */
    data class Transfer(val fileQuery: String, val fromId: String, val toId: String) : PcRoute
}

/**
 * Разбор адреса в голосовой фразе: какому из привязанных ПК она адресована.
 *
 * Почему правила, а не модель. Имена ПК у каждого свои («Рабочий ПК»,
 * «Ноутбук Рамиза»), их знает только телефон — модель на релее их не видела
 * и выучить не могла. А ошибка здесь дорогая: команда уйдёт не на тот
 * компьютер. Поэтому разбор строгий: ПК выбирается, только если имя совпало
 * однозначно, иначе фраза идёт как раньше — на выбранный в приложении ПК.
 *
 * Русские окончания («на рабочем», «с домашнего») снимаются грубым стеммингом:
 * у имени и у слова фразы отрезаются типовые окончания, сравниваются основы.
 * Для пары десятков имён ПК этого хватает, а словарь морфологии на телефоне
 * был бы тяжелее всего остального разбора.
 */
object PcAddressing {

    /** Окончания, которые снимаем; длинные раньше коротких — иначе «ему» стало бы «ем». */
    private val ENDINGS = listOf(
        "ами", "ями", "ому", "ему", "ого", "его", "ими", "ыми",
        "ах", "ях", "ам", "ям", "ий", "ый", "ой", "ем", "ом", "ая", "яя", "ую", "юю", "ей", "ов", "ев",
        "е", "а", "у", "ы", "и", "я", "ю", "о", "ь",
    )

    /**
     * Основы слов «вообще компьютер»: они не выбирают ПК, но и не прерывают его
     * имя («на рабочем компьютере»). «Ноутбук» сюда намеренно не входит: он
     * отличает «Рабочий ноутбук» от «Рабочего ПК».
     */
    private val GENERIC = setOf("пк", "компьютер", "машин", "устройств")

    /** Разговорные сокращения -> основа полного слова. */
    private val SYNONYMS = mapOf("ноут" to "ноутбук", "комп" to "компьютер")

    /** Глаголы переноса файла с ПК на ПК. */
    private val TRANSFER_VERBS = setOf(
        "перекинь", "перенеси", "скопируй", "скинь", "передай", "отправь", "перешли", "закинь",
    )

    /** Слова, которые не входят в имя файла: «перекинь ФАЙЛ отчёт». */
    private val FILE_WORDS = setOf("файл", "файлы", "файлик", "документ", "мой", "мою", "мои")

    /** Нормализация для сравнения: строчные, ё→е, без знаков препинания по краям. */
    internal fun norm(word: String): String =
        word.lowercase().replace('ё', 'е').trim { !it.isLetterOrDigit() && it != '-' && it != '_' }

    /** Основа слова: снимаем одно типовое окончание, оставляя хотя бы три буквы. */
    internal fun stem(word: String): String {
        val w = norm(word)
        if (w.length <= 3) return SYNONYMS[w] ?: w
        for (e in ENDINGS) {
            if (w.endsWith(e) && w.length - e.length >= 3) {
                val s = w.dropLast(e.length)
                return SYNONYMS[s] ?: s
            }
        }
        return SYNONYMS[w] ?: w
    }

    /** Все основы имени ПК. «Рабочий ПК» -> {рабоч, пк}. */
    private fun keyStems(pc: PcName): Set<String> =
        pc.name.split(Regex("[\\s_]+")).map(::stem).filter { it.isNotEmpty() }.toSet()

    /**
     * Узнать ПК по словам фразы, начиная с [start]. Возвращает id и индекс
     * первого слова после имени, либо null, если ПК не узнан однозначно.
     */
    private fun matchPc(tokens: List<String>, start: Int, pcs: List<PcName>): Pair<String, Int>? {
        val keys = pcs.associate { it.id to keyStems(it) }
        val specific = keys.mapValues { it.value - GENERIC }
        val allSpecific = specific.values.flatten().toSet()
        var i = start
        val seen = mutableSetOf<String>()        // значимые слова имени («рабоч», «ноутбук»)
        val seenGeneric = mutableSetOf<String>() // «пк», «компьютере»
        // Берём подряд идущие слова, которые похожи на часть имени ПК или на
        // «пк/компьютере»; первое постороннее слово — конец имени.
        while (i < tokens.size) {
            val s = stem(tokens[i])
            when (s) {
                in GENERIC -> seenGeneric += s
                in allSpecific -> seen += s
                else -> break
            }
            i++
        }
        // «На рабочем столе» — это папка, а не ПК «Рабочий».
        if (i < tokens.size && stem(tokens[i]) == "стол") return null
        val candidates = when {
            // Подходит ПК, в имени которого есть все названные значимые слова.
            seen.isNotEmpty() -> pcs.filter { specific.getValue(it.id).containsAll(seen) }
            // Одни общие слова («на компьютере») ПК не выбирают — кроме случая,
            // когда сам ПК так и назван, без других слов («Компьютер»).
            seenGeneric.isNotEmpty() -> pcs.filter {
                specific.getValue(it.id).isEmpty() && keys.getValue(it.id).any { k -> k in seenGeneric }
            }
            else -> emptyList()
        }
        // Два подходящих — не гадаем: лучше выполнить на выбранном ПК, чем не на том.
        return if (candidates.size == 1) candidates.single().id to i else null
    }

    private fun join(tokens: List<String>): String =
        tokens.joinToString(" ").trim { it == ',' || it == ' ' || it == ':' || it == '—' || it == '-' }

    /** Сколько слов подряд с [from] — общие («компьютерах», «пк»): их пропускаем после «на всех». */
    private fun skipGeneric(tokens: List<String>, from: Int): Int {
        var i = from
        while (i < tokens.size && stem(tokens[i]) in GENERIC) i++
        return i
    }

    /** Кому адресована фраза [text], если привязаны ПК [pcs]. */
    fun route(text: String, pcs: List<PcName>): PcRoute {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || pcs.isEmpty()) return PcRoute.Active
        val n = tokens.size
        fun word(i: Int) = norm(tokens[i])

        // 1. Перенос файла: «<глагол> <что> с <ПК> на <ПК>».
        val verb = tokens.indices.firstOrNull { word(it) in TRANSFER_VERBS }
        if (verb != null) {
            for (c in verb + 1 until n) {
                if (word(c) !in setOf("с", "со", "из")) continue
                val from = matchPc(tokens, c + 1, pcs) ?: continue
                val (fromId, afterFrom) = from
                if (afterFrom >= n || word(afterFrom) !in setOf("на", "в")) continue
                val to = matchPc(tokens, afterFrom + 1, pcs) ?: continue
                val (toId, afterTo) = to
                if (afterTo != n || toId == fromId) continue
                val query = join(tokens.subList(verb + 1, c).filter { norm(it) !in FILE_WORDS })
                if (query.isNotBlank()) return PcRoute.Transfer(query, fromId, toId)
            }
        }

        // 2. Все ПК: «на всех (компьютерах) …», «… на всех», «везде …».
        if (n >= 2 && word(0) == "на" && word(1) in setOf("всех", "всем")) {
            val rest = join(tokens.subList(skipGeneric(tokens, 2), n))
            if (rest.isNotBlank()) return PcRoute.All(rest)
        }
        if (word(0) == "везде" && n > 1) return PcRoute.All(join(tokens.subList(1, n)))
        for (i in n - 2 downTo 1) {
            if (word(i) == "на" && word(i + 1) in setOf("всех", "всем") && skipGeneric(tokens, i + 2) == n) {
                return PcRoute.All(join(tokens.subList(0, i)))
            }
        }
        if (n > 1 && word(n - 1) == "везде") return PcRoute.All(join(tokens.subList(0, n - 1)))

        // 3. Один ПК в начале: «на рабочем (пк) открой почту».
        if (word(0) == "на") {
            val m = matchPc(tokens, 1, pcs)
            if (m != null && m.second < n) return PcRoute.One(m.first, join(tokens.subList(m.second, n)))
        }
        // 4. Один ПК в конце: «открой почту на рабочем».
        for (i in n - 2 downTo 1) {
            if (word(i) != "на") continue
            val m = matchPc(tokens, i + 1, pcs)
            if (m != null && m.second == n) return PcRoute.One(m.first, join(tokens.subList(0, i)))
        }
        return PcRoute.Active
    }

    /**
     * Какие файлы из [names] подходят под слова человека [query] («отчёт по
     * практике»). Точное совпадение имени (с расширением или без) выигрывает
     * сразу; иначе — все файлы, в имени которых есть основы всех слов запроса.
     * Несколько подходящих — решать человеку, а не угадывать.
     */
    fun matchFiles(query: String, names: List<String>): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        fun plain(name: String) = name.lowercase().replace('ё', 'е')
        names.firstOrNull { plain(it) == q || plain(it).substringBeforeLast('.') == q }?.let { return listOf(it) }
        // Короткие слова («по», «за», «в») — предлоги, в имени файла их обычно нет.
        val all = query.split(Regex("\\s+")).map(::stem).filter { it.isNotEmpty() }
        val stems = all.filter { it.length >= 3 }.ifEmpty { all }
        return names.filter { name -> stems.all { plain(name).contains(it) } }
    }
}
