package com.jarvis.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Что скачалось в «Загрузки» за время браузерной задачи.
 *
 * <h2>Зачем</h2>
 * В цепочке «скачай файл и отправь его» следующему шагу нужен ТОЧНЫЙ путь к
 * файлу. Раньше его пришлось бы угадывать по словам модели («скачал отчёт»),
 * а у Chrome имя файла на диске часто другое: «report (1).pdf», «Отчёт_2026.pdf».
 * Здесь путь узнаёт код: запоминает, что лежало в папке до задачи, и после
 * задачи смотрит, что добавилось.
 *
 * <p>Заодно решается вторая проблема: модель говорит «готово», как только
 * нажала «Скачать», а файл ещё качается (Chrome пишет его как *.crdownload).
 * Отправлять недокачанный файл нельзя — поэтому ждём, пока загрузки закончатся.
 *
 * <p>Итог — строки «факт: скачан файл …» в трейсе задачи. Их читает
 * PlanRunner и передаёт следующим подзадачам плана.
 */
public final class DownloadWatcher {

    /**
     * Начало строки трейса с фактом, установленным кодом (а не словами
     * модели). PlanRunner собирает такие строки в итог подзадачи.
     */
    public static final String FACT_MARK = "факт: ";

    private static final long POLL_MS = 300;

    private final Path dir;
    private final Set<Path> before;

    private DownloadWatcher(Path dir, Set<Path> before) {
        this.dir = dir;
        this.before = before;
    }

    /** Запомнить, что лежит в «Загрузках» сейчас, — перед началом задачи. */
    public static DownloadWatcher start() {
        return start(FileTools.downloadsDir());
    }

    static DownloadWatcher start(Path dir) {
        return new DownloadWatcher(dir, list(dir));
    }

    /**
     * Дождаться окончания загрузок, начатых после {@link #start}, и описать их.
     *
     * <p>Если ничего не качается — возвращается сразу, без ожидания: обычные
     * задачи («найди видео») не должны становиться дольше из-за этой проверки.
     * Ждать сразу нечего и в начале загрузки: модель отвечает «готово» минимум
     * через один запрос после клика «Скачать», а Chrome создаёт *.crdownload
     * почти мгновенно — к этому моменту файл загрузки уже виден.
     *
     * @param maxWait   сколько ждать недокачанные файлы
     * @param cancelled отмена задачи прерывает ожидание
     * @return строки для трейса; пусто — ничего не скачивалось
     */
    public List<String> finish(Duration maxWait, BooleanSupplier cancelled) {
        long until = System.currentTimeMillis() + maxWait.toMillis();
        while (true) {
            List<Path> fresh = new ArrayList<>();
            List<Path> partial = new ArrayList<>();
            for (Path p : list(dir)) {
                if (before.contains(p)) continue;
                (FileTools.isPartialDownload(p) ? partial : fresh).add(p);
            }
            boolean timeUp = System.currentTimeMillis() > until || cancelled.getAsBoolean();
            if (partial.isEmpty() || timeUp) {
                List<String> facts = new ArrayList<>();
                for (Path f : fresh) {
                    facts.add(FACT_MARK + "скачан файл " + f + " (" + FileTools.sizeLabel(f) + ")");
                }
                for (Path p : partial) {
                    facts.add(FACT_MARK + "загрузка не закончилась за " + maxWait.toSeconds() + " с: "
                              + p.getFileName() + " — этот файл пока отправлять нельзя");
                }
                return facts;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }

    /** Только верхний уровень папки: браузер кладёт загрузки прямо в неё. */
    private static Set<Path> list(Path dir) {
        Set<Path> out = new HashSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException ignored) {
            // Нет доступа — считаем, что ничего не скачано; задача от этого не падает.
        }
        return out;
    }
}
