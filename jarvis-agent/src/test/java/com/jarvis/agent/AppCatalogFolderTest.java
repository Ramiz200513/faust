package com.jarvis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** open_app с путём к папке: внутри разрешённых — можно, наружу — нельзя. */
class AppCatalogFolderTest {

    @Test
    void cannotEscapeAllowedFolder() {
        java.nio.file.Path documents = java.nio.file.Paths.get(System.getProperty("user.home"), "Documents");
        // Результат может быть пустым или самой папкой «Документы» — но не тем, что выше неё.
        AppCatalog.resolve("Документы\\..\\..").ifPresent(t -> {
            if (t instanceof AppCatalog.Target.Folder f) {
                assertTrue(f.path().startsWith(documents), "«..» вывел наружу: " + f.path());
            }
        });
    }

    @Test
    void missingSubfolder_isNotAFolderTarget() {
        assertFalse(AppCatalog.resolve("Загрузки\\нет-такой-папки-4815162342").orElse(null)
                instanceof AppCatalog.Target.Folder);
    }

    @Test
    void plainAlias_stillWorks() {
        assertInstanceOf(AppCatalog.Target.Folder.class, AppCatalog.resolve("Документы").orElseThrow());
    }
}
