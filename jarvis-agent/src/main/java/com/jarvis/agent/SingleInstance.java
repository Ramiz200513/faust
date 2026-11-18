package com.jarvis.agent;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Один агент на пользователя.
 *
 * <h2>Зачем</h2>
 * С установленной программой агент запускают и автозапуск Windows, и ярлык на
 * рабочем столе, и «Пуск». Без защиты легко получить два агента сразу: оба
 * подключатся к релею под одним токеном, будут вытеснять друг друга, выполнят
 * одну команду дважды и будут драться за мышь.
 *
 * <h2>Как</h2>
 * Блокировка файла {@code ~/.jarvis-agent/agent.lock} средствами ОС. Её держит
 * живой процесс; умер процесс (даже аварийно, через диспетчер задач) — ОС сама
 * снимает блокировку, и «зависших» замков, которые надо удалять руками, не бывает.
 */
public final class SingleInstance {

    private SingleInstance() {}

    /** Держим ссылки, пока жив процесс: собранный сборщиком мусора канал отпустил бы блокировку. */
    private static FileChannel channel;
    private static FileLock lock;

    /** Файл блокировки. Свойство faust.lockFile — для тестов. */
    static Path file() {
        String override = System.getProperty("faust.lockFile");
        return override != null ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jarvis-agent", "agent.lock");
    }

    /**
     * Занять место единственного агента.
     *
     * @return true — мы первые и единственные; false — агент уже работает
     */
    public static synchronized boolean acquire() {
        if (lock != null) return true;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            channel = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                channel = null;
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            return false;                     // тот же процесс уже держит замок другим каналом
        } catch (IOException e) {
            // Не смогли даже проверить (нет прав на папку) — не мешаем запуску:
            // лучше редкий риск двух агентов, чем агент, который не запускается вовсе.
            System.err.println("не удалось проверить, запущен ли агент: " + e.getMessage());
            return true;
        }
    }

    /** Отпустить (для тестов; в работе блокировку снимает завершение процесса). */
    static synchronized void release() {
        try {
            if (lock != null) lock.release();
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // и так закрывается
        }
        lock = null;
        channel = null;
    }
}
