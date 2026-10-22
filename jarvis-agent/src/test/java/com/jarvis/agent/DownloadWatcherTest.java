package com.jarvis.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Слежение за «Загрузками» — на временной папке. Загрузку Chrome изображаем
 * так же, как он её делает: сначала *.crdownload, потом переименование.
 */
class DownloadWatcherTest {

    @TempDir Path dir;

    @Test
    void nothingDownloaded_returnsImmediately() throws Exception {
        Files.writeString(dir.resolve("старый.pdf"), "x");
        DownloadWatcher w = DownloadWatcher.start(dir);

        long t0 = System.currentTimeMillis();
        List<String> facts = w.finish(Duration.ofSeconds(30), () -> false);

        assertTrue(facts.isEmpty(), "старые файлы — не наши загрузки");
        assertTrue(System.currentTimeMillis() - t0 < 500, "обычная задача не должна ждать");
    }

    @Test
    void finishedDownload_isReportedWithFullPath() throws Exception {
        DownloadWatcher w = DownloadWatcher.start(dir);
        Path f = Files.writeString(dir.resolve("report (1).pdf"), "x");

        List<String> facts = w.finish(Duration.ofSeconds(30), () -> false);

        assertEquals(1, facts.size());
        assertTrue(facts.get(0).startsWith(DownloadWatcher.FACT_MARK + "скачан файл " + f), facts.get(0));
    }

    @Test
    void unfinishedDownload_isAwaited() throws Exception {
        DownloadWatcher w = DownloadWatcher.start(dir);
        Path partial = Files.writeString(dir.resolve("Unconfirmed 123.crdownload"), "x");
        new Thread(() -> {
            try {
                Thread.sleep(700);
                Files.move(partial, dir.resolve("report.pdf"));
            } catch (Exception ignored) { }
        }).start();

        List<String> facts = w.finish(Duration.ofSeconds(10), () -> false);

        assertEquals(1, facts.size(), facts.toString());
        assertTrue(facts.get(0).contains("report.pdf"), "отчитываемся о готовом файле, а не о .crdownload");
    }

    @Test
    void stuckDownload_isReportedAsNotFinished() throws Exception {
        DownloadWatcher w = DownloadWatcher.start(dir);
        Files.writeString(dir.resolve("big.zip.crdownload"), "x");

        List<String> facts = w.finish(Duration.ofSeconds(1), () -> false);

        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("не закончилась"), facts.get(0));
        assertTrue(facts.get(0).contains("отправлять нельзя"), facts.get(0));
    }
}
