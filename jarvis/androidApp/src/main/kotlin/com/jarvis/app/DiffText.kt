package com.jarvis.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Текст, в котором может быть дифф (вопрос агента перед коммитом).
 *
 * Агент шлёт вопрос обычным текстом: заголовок, статистика git, строки диффа,
 * сам вопрос. Здесь эти части различаются по виду строк и рисуются как в
 * редакторе кода: добавленное — зелёным, удалённое — красным, имя файла —
 * жирным, всё моноширинным шрифтом и с прокруткой вбок (строки кода длинные,
 * а переносить их — значит сделать дифф нечитаемым).
 *
 * Если диффа в тексте нет, это обычный [Text] — для всех прочих вопросов
 * ничего не меняется.
 */
@Composable
fun DiffAwareText(text: String, modifier: Modifier = Modifier) {
    val lines = text.split("\n")
    val firstCode = lines.indexOfFirst { isCodeLine(it) }
    if (firstCode < 0 || lines.none { it.startsWith("@@") || it.startsWith("▸ ") }) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        return
    }
    val lastCode = lines.indexOfLast { isCodeLine(it) }
    Column(modifier) {
        val head = lines.subList(0, firstCode).joinToString("\n").trim()
        if (head.isNotEmpty()) Text(head, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                // Ширина колонки — по самой длинной строке, и каждая строка тянется
                // на неё целиком: подсветка добавленного/удалённого идёт полосой,
                // как в редакторе, а не обрывается на конце текста.
                Column(Modifier.width(IntrinsicSize.Max)) {
                    lines.subList(firstCode, lastCode + 1).forEach { DiffLine(it) }
                }
            }
        }
        val tail = lines.subList(lastCode + 1, lines.size).joinToString("\n").trim()
        if (tail.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tail, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Строка, которая относится к блоку кода: дифф, статистика git, имя файла. */
private fun isCodeLine(line: String): Boolean =
    line.startsWith("+") || line.startsWith("-") || line.startsWith("@@") || line.startsWith("▸ ") ||
        (line.startsWith(" ") && line.isNotBlank()) || line.startsWith("…") ||
        Regex("""^\s*\S.*\|\s+\d+""").containsMatchIn(line) || Regex("""\d+ files? changed""").containsMatchIn(line)

/** Зелёный и красный, которые читаются на тёмной теме приложения. */
private val ADDED = Color(0xFF3FB950)
private val REMOVED = Color(0xFFF85149)

@Composable
private fun DiffLine(line: String) {
    val (bg, fg, bold) = when {
        line.startsWith("▸ ") -> Triple(Color.Transparent, MaterialTheme.colorScheme.primary, true)
        line.startsWith("@@") -> Triple(Color.Transparent, LocalContentColor.current.copy(alpha = 0.5f), false)
        line.startsWith("+") -> Triple(ADDED.copy(alpha = 0.16f), ADDED, false)
        line.startsWith("-") -> Triple(REMOVED.copy(alpha = 0.16f), REMOVED, false)
        else -> Triple(Color.Transparent, LocalContentColor.current.copy(alpha = 0.85f), false)
    }
    Text(
        text = line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = fg,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}
