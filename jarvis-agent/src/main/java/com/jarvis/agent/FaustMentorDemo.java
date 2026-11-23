package com.jarvis.agent;

import com.jarvis.agent.vision.UiaElement;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/**
 * Показ режима наставника без релея, телефона и модели — посмотреть оверлей глазами и
 * записать видео для защиты:
 * <pre>
 *   ./gradlew runMentorDemo
 * </pre>
 * Сценарий ~20 с: стрелка к элементу слева, «не сюда», стрелка к элементу справа, подсказка
 * без элемента («Нажмите Enter»), «Готово!». Голос ПК читает шаги (если включён и есть
 * русский голос). Клики проходят сквозь подсказку; кнопки панели печатают нажатое в консоль.
 */
public final class FaustMentorDemo {

    private FaustMentorDemo() {}

    public static void main(String[] args) throws Exception {
        Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        FaustMentorView view = new FaustMentorView(w -> System.out.println("кнопка панели: " + w),
                PcVoice.openIfEnabled());
        UiaElement left = new UiaElement(1, "listitem", "Специальные возможности",
                s.x + s.width / 5, s.y + s.height / 3, 260, 40, false, true, "", false);
        UiaElement right = new UiaElement(2, "button", "Экранная лупа",
                s.x + s.width * 3 / 5, s.y + s.height / 2, 200, 44, false, true, "", false);

        view.start("включить экранную лупу");
        Thread.sleep(2500);
        view.point(left, "Нажмите «Специальные возможности»", "Нажмите Специальные возможности.", 1, 3);
        Thread.sleep(4500);
        view.wrong("Не сюда. Нажмите на подсвеченное.");
        Thread.sleep(3000);
        view.point(right, "Нажмите «Экранная лупа»", "Нажмите Экранная лупа.", 2, 3);
        Thread.sleep(4500);
        view.hint("Нажмите Enter", "Нажмите Enter.", 3, 3);
        Thread.sleep(3500);
        view.done("Готово! Вы сделали это сами", "Готово! Вы сделали это сами.");
        view.end();
        Thread.sleep(4500);
        System.exit(0);
    }
}
