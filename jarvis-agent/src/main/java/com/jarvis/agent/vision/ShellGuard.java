package com.jarvis.agent.vision;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Запрет ИИ-агенту на терминалы и системные инструменты.
 *
 * <h2>Откуда взялся (найдено на живом прогоне 30.09)</h2>
 * В задаче «скачай архив и распакуй его» агент, не найдя сразу кнопку, сам
 * открыл PowerShell через open_app и набрал {@code Expand-Archive ... -Force}.
 * Цель была безобидной, но путь — нет: через терминал модель может выполнить
 * ЛЮБУЮ команду, в обход белого списка релея, подтверждений и всех проверок.
 * Win+R и Alt+F4 были запрещены и раньше; оказалось, что этого мало.
 *
 * <p>Три рубежа, все — кодом, а не просьбой в промпте:
 * <ol>
 *   <li>нельзя открыть терминал или системный инструмент ({@link #isForbiddenApp});</li>
 *   <li>нельзя печатать в окно терминала, даже если его открыл человек ({@link #isShellWindow});</li>
 *   <li>нельзя напечатать текст, похожий на команду запуска, — адресная строка
 *       Проводника тоже выполняет «powershell» или «cmd» ({@link #looksLikeCommand}).</li>
 * </ol>
 * С телефона человек по-прежнему может открыть любую программу командой
 * open_app — запрет касается только решений модели.
 */
public final class ShellGuard {

    /** Терминалы и инструменты, меняющие систему. Сравнение — по вхождению в нормализованное имя. */
    private static final List<String> FORBIDDEN_APPS = List.of(
            "powershell", "pwsh", "cmd", "командная строка", "command prompt", "terminal", "терминал",
            "wsl", "bash", "ubuntu", "regedit", "редактор реестра", "registry editor",
            "планировщик заданий", "task scheduler", "службы", "services", "gpedit",
            "редактор локальной групповой политики", "msconfig", "конфигурация системы",
            "управление компьютером", "computer management", "mmc");

    /** Заголовки окон терминалов — печатать туда значит выполнять команды. */
    private static final Pattern SHELL_TITLE = Pattern.compile(
            "(?iu)(powershell|pwsh|cmd\\.exe|командная строка|command prompt|windows terminal|терминал|wsl|bash|ubuntu)");

    /**
     * Текст, похожий на команду: начинается с имени оболочки или опасной утилиты,
     * либо запускает файл-сценарий/программу.
     */
    private static final Pattern COMMAND = Pattern.compile(
            "(?iu)(^\\s*(powershell|pwsh|cmd|wsl|bash|reg|regedit|msiexec|rundll32|mshta|wscript|cscript|"
            + "certutil|bitsadmin|schtasks|sc|net|format|del|erase|rd|rmdir|shutdown|taskkill|"
            + "start|call|iex|invoke-\\w+|remove-item|expand-archive|set-executionpolicy)(\\s|$|/|\\.exe))"
            + "|(\\.(exe|bat|cmd|ps1|vbs|js|msi|scr|lnk)\\b)");

    private ShellGuard() {}

    /** Модели нельзя открывать эту программу (open_app). */
    public static boolean isForbiddenApp(String appName) {
        if (appName == null) return false;
        String n = appName.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String bad : FORBIDDEN_APPS) {
            // «cmd» и «mmc» — только целым словом, чтобы не задеть случайные совпадения внутри имён.
            if (bad.length() <= 4 ? Pattern.compile("(?<![a-zа-я])" + Pattern.quote(bad) + "(?![a-zа-я])").matcher(n).find()
                                  : n.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    /** Это окно терминала — печатать в него нельзя. */
    public static boolean isShellWindow(String windowTitle) {
        return windowTitle != null && SHELL_TITLE.matcher(windowTitle).find();
    }

    /** Текст похож на команду запуска — печатать его нельзя ни в какое поле. */
    public static boolean looksLikeCommand(String text) {
        return text != null && COMMAND.matcher(text).find();
    }

    /** Подсказка модели, как сделать то же без терминала. */
    public static final String HINT = "терминалы и системные инструменты агенту запрещены — делай это обычными "
            + "программами, а zip-архив распакуй действием extract_archive";
}
