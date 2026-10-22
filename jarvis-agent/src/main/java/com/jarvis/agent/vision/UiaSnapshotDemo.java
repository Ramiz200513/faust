package com.jarvis.agent.vision;

/**
 * Ручная проверка режима UI Automation без релея, модели и телефона: печатает
 * то, что агент увидел бы в активном окне.
 *
 * <pre>
 *   ./gradlew runUiaDemo                       снимок через 5 секунд (успеть переключиться на нужное окно)
 *   ./gradlew runUiaDemo --args="0"            снимок сразу
 *   ./gradlew runUiaDemo --args="0 Telegram"   снимок окна с таким заголовком, без переключения
 * </pre>
 *
 * Пауза нужна потому, что снимается окно, которое СЕЙЧАС активно: пока идёт
 * запуск через Gradle, активной будет консоль, а не проверяемая программа.
 * Второй аргумент — часть заголовка окна — позволяет обойтись без переключения:
 * удобно, когда прогон запускается из скрипта.
 */
public class UiaSnapshotDemo {

    public static void main(String[] args) throws Exception {
        int delaySeconds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String windowTitlePart = args.length > 1 ? args[1] : null;

        if (windowTitlePart != null) {
            dumpByTitle(windowTitlePart);
            return;
        }

        if (delaySeconds > 0) {
            System.out.println("Переключитесь на нужное окно — снимок через " + delaySeconds + " с...");
            for (int i = delaySeconds; i > 0; i--) {
                System.out.println("  " + i);
                Thread.sleep(1000);
            }
        }

        print(new UiaSnapshot().captureForegroundWindow());
    }

    /** Найти окно по части заголовка и снять его, не трогая фокус. */
    private static void dumpByTitle(String titlePart) {
        final java.util.List<com.sun.jna.platform.win32.WinDef.HWND> found = new java.util.ArrayList<>();
        com.sun.jna.platform.win32.User32.INSTANCE.EnumWindows((hwnd, data) -> {
            char[] buf = new char[512];
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = com.sun.jna.Native.toString(buf);
            if (!title.isBlank() && title.toLowerCase().contains(titlePart.toLowerCase())
                    && com.sun.jna.platform.win32.User32.INSTANCE.IsWindowVisible(hwnd)) {
                found.add(hwnd);
                return false; // хватит первого подходящего
            }
            return true;
        }, null);

        if (found.isEmpty()) {
            System.out.println("Окно с заголовком, содержащим \"" + titlePart + "\", не найдено");
            return;
        }
        print(new UiaSnapshot().capture(com.sun.jna.Pointer.nativeValue(found.get(0).getPointer())));
    }

    private static void print(UiaSnapshot.Result result) {
        System.out.println();
        System.out.println("Окно: \"" + result.windowTitle() + "\"");
        System.out.println("Снимок занял: " + result.elapsedMs() + " мс");
        System.out.println("Элементов после чистки: " + result.elements().size()
                + (result.isUsable() ? " — хватает для режима UIA"
                                     : " — слишком мало, агент откатится на пиксельный режим"));
        System.out.println();

        for (UiaElement e : result.elements()) {
            System.out.println("  " + e + "   -> клик в (" + e.centerX() + ", " + e.centerY() + ")");
        }
    }
}
