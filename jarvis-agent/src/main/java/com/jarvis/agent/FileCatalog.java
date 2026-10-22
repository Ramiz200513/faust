package com.jarvis.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Разрешённые для передачи файлов папки — переиспользует те же алиасы, что и
 * open_app (Downloads/Documents/Desktop/Pictures из AppCatalog), но требует
 * именно Folder, а не программу/браузер. Отдельный класс, а не метод в
 * AppCatalog — у него другая ответственность: проверка имени файла и пути,
 * а не запуск программы.
 */
public final class FileCatalog {

    /** Жёсткий потолок на размер одного файла: 5 МБ raw (~6.7 МБ в base64) —
     *  с запасом укладывается в 8 МБ лимит WS-сообщения на релее. */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private FileCatalog() {}

    /** Резолвит имя папки (как в open_app) в реальный путь — только если это папка, не программа. */
    public static Optional<Path> resolveFolder(String folderName) {
        return AppCatalog.resolve(folderName)
                .filter(t -> t instanceof AppCatalog.Target.Folder)
                .map(t -> ((AppCatalog.Target.Folder) t).path());
    }

    /**
     * Безопасно резолвит имя файла ВНУТРИ папки — блокирует "../" и абсолютные
     * пути, которыми можно было бы выйти за пределы разрешённой папки.
     */
    public static Optional<Path> resolveFile(Path folder, String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        Path resolved = folder.resolve(fileName).normalize();
        if (!resolved.startsWith(folder)) return Optional.empty(); // попытка выйти из папки
        return Optional.of(resolved);
    }

    /** Список файлов (не папок) внутри — имя и размер. */
    public static List<FileEntry> list(Path folder) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(new FileEntry(p.getFileName().toString(), Files.size(p)));
                }
            }
        }
        return out;
    }

    public record FileEntry(String name, long sizeBytes) {}
}
