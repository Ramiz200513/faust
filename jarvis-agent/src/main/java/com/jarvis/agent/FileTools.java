package com.jarvis.agent;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Точные инструменты для работы с файлами — то, что ИИ-агент делает КОДОМ,
 * а не глазами (этап 3 многоэтапных задач).
 *
 * <h2>Зачем</h2>
 * Самое хрупкое место в задаче «скачай файл и отправь его» — прикрепить файл
 * в программе. Обычный путь — кнопка-скрепка, системное окно «Открыть»,
 * навигация по папкам, выбор файла — это 5–8 шагов, в каждом из которых
 * модель может промахнуться. Здесь два действия заменяют весь этот путь:
 * <ul>
 *   <li>{@link #find} — найти файл по части имени в разрешённых папках;</li>
 *   <li>{@link #copyToClipboard} — положить файл в буфер обмена так же, как
 *       это делает «Копировать» в Проводнике. После Ctrl+V мессенджер
 *       прикрепляет файл к сообщению, а Проводник — копирует его в папку.</li>
 * </ul>
 * Инструменты общие, не «про чаты»: вставка файла работает в любой программе,
 * которая умеет принимать файлы из буфера (Проводник, почтовые клиенты,
 * Telegram, WhatsApp, Word).
 *
 * <h2>Безопасность</h2>
 * Работать можно только с файлами в тех же папках, что уже разрешены для
 * передачи на телефон: Загрузки, Документы, Рабочий стол, Изображения.
 * Папка агента {@code .jarvis-agent} (там токен) недоступна никак.
 */
public final class FileTools {

    /** Разрешённые папки — те же, что у команд передачи файлов (FileCatalog). */
    private static final List<String> ROOT_ALIASES = List.of("загрузки", "документы", "рабочий стол", "изображения");
    /** Больше результатов модели не нужно: ей выбирать из списка, а не читать каталог. */
    private static final int MAX_RESULTS = 5;
    /** Глубина обхода: «Документы/Учёба/Диплом/2026» — уже 4 уровня. */
    private static final int MAX_DEPTH = 4;
    /** Потолок обхода — на случай огромной папки: поиск не должен висеть. */
    private static final int MAX_VISITED = 20_000;
    private static final long MAX_SEARCH_MS = 2_000;
    /** Папки, в которых человек свои файлы не хранит, а обход по ним долгий. */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "$recycle.bin", ".git", ".gradle", ".idea");

    private FileTools() {}

    /** Папки, в которых агенту разрешено искать и брать файлы. */
    public static List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String alias : ROOT_ALIASES) {
            FileCatalog.resolveFolder(alias).filter(Files::isDirectory).ifPresent(roots::add);
        }
        return roots;
    }

    // --- поиск --------------------------------------------------------------

    /** Найти файлы, в имени которых есть все слова запроса. Новые — первыми. */
    public static List<Path> find(String query) {
        return find(query, allowedRoots());
    }

    /**
     * То же, но в заданных папках (для тестов).
     *
     * <p>Совпадение — «все слова запроса есть в имени», причём и как написано,
     * и в транслите: «отчёт июнь» найдёт и «Отчёт_июнь.docx», и «otchet-iyun.pdf».
     * Новые файлы — первыми: в многоэтапной задаче нужный почти всегда тот,
     * что только что скачали.
     */
    static List<Path> find(String query, List<Path> roots) {
        List<String> words = words(query);
        if (words.isEmpty()) return List.of();

        List<Path> found = new ArrayList<>();
        int[] visited = {0};
        long until = System.currentTimeMillis() + MAX_SEARCH_MS;
        for (Path root : roots) {
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(root) && (name.startsWith(".") || SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT)))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (++visited[0] > MAX_VISITED || System.currentTimeMillis() > until) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isRegularFile() && !isPartialDownload(file) && matches(file, words)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE; // нет прав на папку — просто идём дальше
                    }
                });
            } catch (IOException ignored) {
                // Папка пропала посреди обхода — ищем в остальных.
            }
        }
        found.sort(Comparator.comparingLong(FileTools::modified).reversed());
        return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS)) : List.copyOf(found);
    }

    /** Результат поиска строкой для трейса — модель прочтёт её на следующем шаге. */
    public static String describeFound(String query, List<Path> files) {
        if (files.isEmpty()) {
            return "файлов «" + query + "» не найдено в папках: " + rootNames()
                   + " — попробуй другую часть имени или спроси человека";
        }
        StringBuilder sb = new StringBuilder("найдено (новые первыми): ");
        SimpleDateFormat when = new SimpleDateFormat("dd.MM HH:mm");
        for (int i = 0; i < files.size(); i++) {
            Path f = files.get(i);
            if (i > 0) sb.append("; ");
            sb.append(i + 1).append(") ").append(f)
              .append(" (").append(sizeLabel(f)).append(", ").append(when.format(new Date(modified(f)))).append(")");
        }
        return sb.toString();
    }

    // --- проверка пути ---------------------------------------------------------

    /**
     * Путь, который модель хочет вставить, — существующий файл в разрешённой папке?
     *
     * @return нормализованный путь
     * @throws SecurityException        попытка добраться до папки агента (там токен)
     * @throws IllegalArgumentException файла нет или он вне разрешённых папок —
     *                                  это ошибка шага, модель попробует иначе
     */
    public static Path checkAllowed(String rawPath) {
        return checkAllowed(rawPath, allowedRoots());
    }

    static Path checkAllowed(String rawPath, List<Path> roots) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("не указан путь к файлу — сначала найди его через find_file");
        }
        Path p;
        try {
            p = Paths.get(rawPath.strip().replaceAll("^\"|\"$", "")).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalArgumentException("это не путь к файлу: «" + rawPath + "»");
        }
        if (p.toString().toLowerCase(Locale.ROOT).contains(".jarvis-agent")) {
            throw new SecurityException("файлы агента трогать нельзя");
        }
        boolean inside = roots.stream().anyMatch(r -> p.startsWith(r.toAbsolutePath().normalize()));
        if (!inside) {
            throw new IllegalArgumentException("файл вне разрешённых папок (" + rootNames() + "): " + p);
        }
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("файла нет: " + p + " — найди его через find_file");
        }
        return p;
    }

    // --- распаковка zip ------------------------------------------------------------

    /** Защита от «zip-бомбы»: больше этого распаковывать не станем. */
    private static final int MAX_ZIP_ENTRIES = 5_000;
    private static final long MAX_ZIP_BYTES = 500L * 1024 * 1024;

    /**
     * Распаковать zip-архив в папку — узкий безопасный инструмент ВМЕСТО терминала.
     *
     * <h3>Зачем (прогоны 30.09)</h3>
     * Сначала агент распаковал архив через PowerShell — это закрыто (ShellGuard).
     * Потом он распаковывал через контекстное меню Проводника: 30+ шагов и две
     * перестройки плана, потому что меню Windows 11 — отдельное всплывающее окно,
     * которого модель не видит, и она жала стрелки вслепую. Здесь то же самое —
     * одно действие, и только в пределах разрешённых папок.
     *
     * <p>Защиты: архив и папка назначения — только внутри разрешённых папок;
     * «zip slip» (запись в «..\..\Windows» через хитрое имя внутри архива)
     * блокируется; существующие файлы НЕ перезаписываются; есть потолок на
     * число файлов и общий размер.
     *
     * @param spec «путь к архиву|папка назначения» — так их передаёт действие модели
     * @return что распаковано — строка для трейса
     */
    public static String extractZip(String spec) throws IOException {
        return extractZip(spec, allowedRoots());
    }

    /** То же, но в заданных папках (для тестов). */
    static String extractZip(String spec, List<Path> roots) throws IOException {
        if (spec == null || !spec.contains("|")) {
            throw new IllegalArgumentException("нужно указать и архив (path), и папку назначения (to)");
        }
        String[] parts = spec.split("\\|", 2);
        Path zip = checkAllowed(parts[0], roots);
        if (!zip.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("умею распаковывать только .zip: " + zip.getFileName());
        }
        Path dest = resolveDestination(parts[1], roots);
        Files.createDirectories(dest);

        int written = 0, skipped = 0;
        long bytes = 0;
        List<String> sample = new ArrayList<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            if (zf.size() > MAX_ZIP_ENTRIES) {
                throw new IllegalArgumentException("в архиве слишком много файлов: " + zf.size());
            }
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new SecurityException("архив пытается записать файл вне папки назначения: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (Files.exists(target)) { // чужие файлы не трогаем
                    skipped++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var in = zf.getInputStream(e)) {
                    bytes += Files.copy(in, target);
                }
                if (bytes > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("архив больше " + (MAX_ZIP_BYTES >> 20) + " МБ после распаковки");
                }
                written++;
                if (sample.size() < 5) sample.add(dest.relativize(target).toString());
            }
        }
        return "распаковано файлов: " + written + " в " + dest
               + (skipped > 0 ? " (уже были и не перезаписаны: " + skipped + ")" : "")
               + (sample.isEmpty() ? "" : "; например: " + String.join(", ", sample));
    }

    /** Папка назначения: «Документы\ФаустТест», «Загрузки» или полный путь — только внутри разрешённых. */
    static Path resolveDestination(String raw, List<Path> roots) {
        String s = raw == null ? "" : raw.strip().replaceAll("^\"|\"$", "").replace('/', '\\');
        if (s.isEmpty()) throw new IllegalArgumentException("не указана папка назначения");
        Path p;
        Path direct = null;
        try {
            direct = Paths.get(s);
        } catch (Exception ignored) {
            // не путь — пробуем как «Папка\подпапка»
        }
        if (direct != null && direct.isAbsolute()) {
            p = direct.normalize();
        } else {
            int cut = s.indexOf('\\');
            String head = cut < 0 ? s : s.substring(0, cut);
            Path root = FileCatalog.resolveFolder(head.toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new IllegalArgumentException("не понял папку назначения «" + raw + "»"));
            p = cut < 0 ? root : root.resolve(s.substring(cut + 1)).normalize();
        }
        Path finalP = p;
        if (finalP.toString().toLowerCase(Locale.ROOT).contains(".jarvis-agent")
                || roots.stream().noneMatch(r -> finalP.startsWith(r.toAbsolutePath().normalize()))) {
            throw new IllegalArgumentException("папка назначения вне разрешённых (" + rootNames() + "): " + p);
        }
        return p;
    }

    // --- буфер обмена ----------------------------------------------------------

    /**
     * Положить файл в буфер обмена как ФАЙЛ, а не как текст пути.
     *
     * <p>{@link DataFlavor#javaFileListFlavor} Java на Windows переводит в
     * системный формат CF_HDROP — тот самый, что кладёт «Копировать» в
     * Проводнике. Поэтому любая программа, умеющая «вставить файл», поймёт его.
     */
    public static void copyToClipboard(Path file) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new FileSelection(file), null);
    }

    /** Содержимое буфера — список из одного файла. Отдельный класс, чтобы его можно было проверить тестом. */
    static final class FileSelection implements Transferable {
        private final List<File> files;

        FileSelection(Path file) {
            this.files = List.of(file.toFile());
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return files;
        }
    }

    // --- вспомогательное -------------------------------------------------------

    /** Недокачанный файл Chrome/Firefox/Edge — его нельзя ни находить, ни отправлять. */
    public static boolean isPartialDownload(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".crdownload") || n.endsWith(".part") || n.endsWith(".partial")
               || n.endsWith(".download") || n.endsWith(".tmp");
    }

    /** «240 КБ» — для трейса: модели и человеку размер помогает узнать нужный файл. */
    public static String sizeLabel(Path f) {
        try {
            long b = Files.size(f);
            if (b < 1024) return b + " Б";
            if (b < 1024 * 1024) return (b / 1024) + " КБ";
            return String.format(Locale.ROOT, "%.1f МБ", b / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "? Б";
        }
    }

    private static boolean matches(Path file, List<String> words) {
        String name = AppCatalog.norm(file.getFileName().toString());
        String latin = AppCatalog.translit(name);
        for (String w : words) {
            if (!name.contains(w) && !latin.contains(AppCatalog.translit(w))) return false;
        }
        return true;
    }

    private static List<String> words(String query) {
        if (query == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String w : AppCatalog.norm(query).split(" ")) {
            if (!w.isBlank()) out.add(w);
        }
        return out;
    }

    private static long modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String rootNames() {
        return String.join(", ", ROOT_ALIASES);
    }

    /** Папка «Загрузки» — за ней следит DownloadWatcher, в неё качает браузер агента. */
    public static Path downloadsDir() {
        Optional<Path> p = FileCatalog.resolveFolder("загрузки");
        return p.orElse(Paths.get(System.getProperty("user.home"), "Downloads"));
    }
}
