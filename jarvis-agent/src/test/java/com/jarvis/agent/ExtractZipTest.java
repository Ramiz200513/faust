package com.jarvis.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Распаковка zip вместо терминала — и её защиты. */
class ExtractZipTest {

    @TempDir Path downloads;
    @TempDir Path documents;
    @TempDir Path outside;

    /** Собрать zip из пар «имя внутри архива» -> «содержимое». */
    private Path zip(String name, String... entries) throws Exception {
        Path z = downloads.resolve(name);
        try (OutputStream os = Files.newOutputStream(z); ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return z;
    }

    @Test
    void extracts_intoNewFolder() throws Exception {
        Path z = zip("Hello-World-master.zip", "Hello-World-master/README", "Hello World!");
        Path dest = documents.resolve("ФаустТест");

        String note = FileTools.extractZip(z + "|" + dest, List.of(downloads, documents));

        assertEquals("Hello World!", Files.readString(dest.resolve("Hello-World-master/README")));
        assertTrue(note.contains("распаковано файлов: 1"), note);
    }

    @Test
    void zipSlip_isBlocked() throws Exception {
        // Классическая атака: имя внутри архива выводит запись наружу.
        Path z = zip("evil.zip", "../../evil.txt", "x");

        assertThrows(SecurityException.class,
                () -> FileTools.extractZip(z + "|" + documents.resolve("t"), List.of(downloads, documents)));
        assertFalse(Files.exists(documents.getParent().resolve("evil.txt")));
    }

    @Test
    void existingFiles_areNotOverwritten() throws Exception {
        Path dest = Files.createDirectories(documents.resolve("t"));
        Files.writeString(dest.resolve("a.txt"), "мой важный файл");
        Path z = zip("a.zip", "a.txt", "из архива");

        String note = FileTools.extractZip(z + "|" + dest, List.of(downloads, documents));

        assertEquals("мой важный файл", Files.readString(dest.resolve("a.txt")));
        assertTrue(note.contains("не перезаписаны: 1"), note);
    }

    @Test
    void destinationOutsideAllowedFolders_isRejected() throws Exception {
        Path z = zip("a.zip", "a.txt", "x");
        assertThrows(IllegalArgumentException.class,
                () -> FileTools.extractZip(z + "|" + outside, List.of(downloads, documents)));
    }

    @Test
    void onlyZip() throws Exception {
        Path rar = Files.writeString(downloads.resolve("a.rar"), "x");
        assertThrows(IllegalArgumentException.class,
                () -> FileTools.extractZip(rar + "|" + documents, List.of(downloads, documents)));
    }
}
