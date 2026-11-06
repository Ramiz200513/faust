package com.jarvis.agent;

import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.ImageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Видео-отчёт без экрана: кадры приходят готовыми картинками, robot = null.
 * Проверяется, что отчёт собирается, сохраняется, читается обратно для
 * телефона и что GIF — настоящая анимация с нужным числом кадров.
 */
class TaskReportRecorderTest {

    @TempDir
    Path home;
    private String realHome;

    @BeforeEach
    void fakeHome() {
        // Отчёты пишутся в ~/.jarvis-agent/reports — в тесте это временная папка.
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        // Не зависеть от agent.properties на конкретном ПК.
        System.setProperty("faust.report.enabled", "true");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
        System.clearProperty("faust.report.enabled");
    }

    private static byte[] fakeScreenshot(Color color) throws Exception {
        BufferedImage img = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 800, 450);
        g.dispose();
        return ImageUtil.toJpeg(img, 0.8f);
    }

    @Test
    void recordedTask_isSaved_andLoadsBackForThePhone() throws Exception {
        TaskReportRecorder r = TaskReportRecorder.start("task-123", "отправь отчёт Лере", null);
        assertNotNull(r);
        TaskReportRecorder.noteThought("Нажимаю «Отправить»", "файл уже прикреплён");
        r.onStep(1, "шаг 1: click(5)", fakeScreenshot(Color.DARK_GRAY), 400, 200, null);
        r.onStep(2, "шаг 2: type(Лера)\nищу чат Леры", fakeScreenshot(Color.GRAY), null, null, "2/3");
        r.finish(true, "цель достигнута за 2 шаг(ов), 14 с");

        ResultData.TaskReport report = TaskReportRecorder.load("task-123").orElseThrow();
        assertEquals("отправь отчёт Лере", report.goal());
        assertTrue(report.completed());
        List<Integer> steps = report.frames().stream().map(ResultData.ReportFrame::step).toList();
        assertEquals(List.of(0, 1, 2, -1), steps, "титул, два шага, итог");
        // Подпись шага — мысль модели, а не «click(5)».
        assertTrue(report.frames().get(1).caption().contains("Нажимаю «Отправить»"), report.frames().get(1).caption());
        assertTrue(report.frames().get(1).caption().contains("файл уже прикреплён"));
        // Без мысли от экрана — берётся вторая строка описания шага.
        assertTrue(report.frames().get(2).caption().contains("подзадача 2/3"));
        assertTrue(report.frames().get(2).caption().contains("ищу чат Леры"), report.frames().get(2).caption());

        Path gif = TaskReportRecorder.gif("task-123").orElseThrow();
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            assertEquals(4, reader.getNumImages(true), "в GIF столько же кадров, сколько в отчёте");
            assertEquals(TaskReportRecorder.GIF_WIDTH, reader.getWidth(0));
            reader.dispose();
        }
    }

    @Test
    void longTask_keepsTitleAndTheEnd_withinTheLimit() throws Exception {
        TaskReportRecorder r = TaskReportRecorder.start("long", "длинная задача", null);
        byte[] shot = fakeScreenshot(Color.BLACK);
        for (int i = 1; i <= 60; i++) r.onStep(i, "шаг " + i + ": click(1)", shot, null, null, null);
        r.finish(false, "не успел");

        List<ResultData.ReportFrame> frames = TaskReportRecorder.load("long").orElseThrow().frames();
        assertTrue(frames.size() <= TaskReportRecorder.MAX_FRAMES, "кадров: " + frames.size());
        assertEquals(0, frames.get(0).step(), "титульный кадр остаётся");
        assertEquals(60, frames.get(frames.size() - 2).step(), "последний шаг остаётся — конец задачи важнее начала");
        assertEquals(-1, frames.get(frames.size() - 1).step());
    }

    @Test
    void reportId_fromPhone_cannotLeaveReportsFolder() {
        assertEquals("etcpasswd", TaskReportRecorder.safeName("../../etc/passwd"));
        assertEquals("unknown", TaskReportRecorder.safeName("..\\..\\"));
        assertTrue(TaskReportRecorder.load("../../что-угодно").isEmpty());
    }

    @Test
    void switchedOff_inSettings_recordsNothing() {
        System.setProperty("faust.report.enabled", "false");
        assertNull(TaskReportRecorder.start("off", "задача", null));
        assertFalse(Files.exists(home.resolve(".jarvis-agent").resolve("reports").resolve("off")));
    }
}
