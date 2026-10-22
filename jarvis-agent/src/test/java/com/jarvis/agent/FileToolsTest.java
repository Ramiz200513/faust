package com.jarvis.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Поиск файла, проверка пути и содержимое буфера — на временных папках,
 * настоящие «Загрузки» и «Документы» тесты не трогают.
 */
class FileToolsTest {

    @TempDir Path downloads;
    @TempDir Path documents;
    @TempDir Path outside;

    private Path file(Path dir, String name, long ageMinutes) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageMinutes * 60_000));
        return p;
    }

    @Test
    void find_allWordsInName_newestFirst() throws Exception {
        Path old = file(documents, "Отчёт_июнь_2025.docx", 60 * 24 * 365);
        Path fresh = file(downloads, "отчет июнь.pdf", 1);
        file(downloads, "отчёт май.pdf", 5);

        List<Path> found = FileTools.find("отчёт июнь", List.of(downloads, documents));

        assertEquals(List.of(fresh, old), found, "ё/е не важны, новый файл — первым");
    }

    @Test
    void find_worksAcrossAlphabets() throws Exception {
        Path latin = file(downloads, "otchet-final.pdf", 1);
        assertEquals(List.of(latin), FileTools.find("отчет", List.of(downloads)));
    }

    @Test
    void find_skipsHiddenFoldersAndUnfinishedDownloads() throws Exception {
        file(downloads, ".cache/report.pdf", 1);
        file(downloads, "report.pdf.crdownload", 1);
        Path real = file(downloads, "sub/report.pdf", 1);

        assertEquals(List.of(real), FileTools.find("report", List.of(downloads)));
    }

    @Test
    void describeFound_tellsWhatToDoWhenNothingFound() {
        String s = FileTools.describeFound("нечто", List.of());
        assertTrue(s.contains("не найдено"), s);
    }

    @Test
    void checkAllowed_insideRoot_ok() throws Exception {
        Path f = file(downloads, "report.pdf", 1);
        assertEquals(f, FileTools.checkAllowed("\"" + f + "\"", List.of(downloads)), "кавычки вокруг пути — не помеха");
    }

    @Test
    void checkAllowed_outsideRoot_isRejected() throws Exception {
        Path f = file(outside, "secret.txt", 1);
        assertThrows(IllegalArgumentException.class, () -> FileTools.checkAllowed(f.toString(), List.of(downloads)));
        // И выйти из разрешённой папки через «..» тоже нельзя.
        String sneaky = downloads.resolve("..").resolve(outside.getFileName()).resolve("secret.txt").toString();
        assertThrows(IllegalArgumentException.class, () -> FileTools.checkAllowed(sneaky, List.of(downloads)));
    }

    @Test
    void checkAllowed_agentFolder_isSecurityStop() {
        // Даже если бы .jarvis-agent оказалась внутри разрешённой папки — там токен.
        String token = downloads.resolve(".jarvis-agent").resolve("token.properties").toString();
        assertThrows(SecurityException.class, () -> FileTools.checkAllowed(token, List.of(downloads)));
    }

    @Test
    void checkAllowed_missingFile_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FileTools.checkAllowed(downloads.resolve("нет.pdf").toString(), List.of(downloads)));
    }

    @Test
    void clipboardContent_isAFileListLikeExplorerCopy() throws Exception {
        Path f = file(downloads, "report.pdf", 1);
        FileTools.FileSelection sel = new FileTools.FileSelection(f);

        assertTrue(sel.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        assertFalse(sel.isDataFlavorSupported(DataFlavor.stringFlavor), "файл, а не текст пути");
        assertEquals(List.of(f.toFile()), sel.getTransferData(DataFlavor.javaFileListFlavor));
        assertInstanceOf(File.class, ((List<?>) sel.getTransferData(DataFlavor.javaFileListFlavor)).get(0));
    }
}
