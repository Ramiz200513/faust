package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.protocol.ResultData;
import com.jarvis.agent.vision.ImageUtil;
import com.jarvis.agent.vision.UiaElement;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Видео-отчёт о задаче: «почему ты так сделал».
 *
 * <p>Пока агент работает, на каждый шаг сюда приходит кадр — тот же скриншот,
 * что уходит на телефон. Поверх него рисуется то, чего на голом скриншоте не
 * видно: куда агент нажимает (метка), какой элемент окна выбрал (рамка, как у
 * «рентгена»), и что модель при этом думала (подпись). В конце из кадров
 * собирается GIF-анимация, а сами кадры по запросу уходят на телефон
 * (get_task_report), где их можно листать.
 *
 * <p>Зачем отметки рисуются здесь, на ПК, а не на телефоне: тогда GIF,
 * который человек перешлёт преподавателю, и то, что он видит в приложении, —
 * одна и та же картинка. И телефону не нужно знать про UIA-элементы.
 *
 * <p>Хранится на диске ({@code ~/.jarvis-agent/reports/<id>/}), последние
 * {@link #KEEP} задач: отчёт обычно смотрят сразу после задачи или на защите,
 * копить его вечно незачем.
 *
 * <p>Все методы записи «глотают» ошибки: отчёт — украшение, и из-за него
 * задача падать не должна.
 */
public final class TaskReportRecorder {

    /** Больше кадров в отчёт не кладём: ~40 шагов — это уже 2–3 минуты «видео». */
    static final int MAX_FRAMES = 40;
    /** Ширина кадра для телефона и GIF: подписи читаются, а 40 кадров весят ~1,5 МБ. */
    static final int FRAME_WIDTH = 800;
    /** GIF уже, чем кадры: у GIF нет сжатия JPEG, и при 800 px он вырос бы в разы. */
    static final int GIF_WIDTH = 640;
    /** Сколько последних отчётов хранить. */
    static final int KEEP = 20;

    // Цвета — те же, что у «рентгена» на экране ПК (FaustXrayOverlay): человек
    // видит в отчёте ровно ту подсветку, что видел на мониторе.
    private static final Color COPPER = new Color(0xC8845A);
    private static final Color ROSE = new Color(0xE0A0A0);
    private static final Color BAND = new Color(0x1E, 0x18, 0x17, 215);
    private static final Color TEXT = new Color(0xF3E7DA);
    private static final Color MUTED = new Color(0xBFAF9F);
    private static final Color OK = new Color(0x7FC98A);
    private static final Color FAIL = new Color(0xE57373);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Отчёт идущей задачи — сюда подсказки шлёт FaustOverlayController
     * (мысль модели, элементы окна, выбранный элемент). Задача одна за раз
     * (busyGoal в CommandExecutor), поэтому одной ссылки достаточно.
     */
    private static volatile TaskReportRecorder current;

    private final String taskId;
    private final String goal;
    private final Path dir;
    private final List<Frame> frames = new ArrayList<>();
    private final Robot robot;

    // Подсказки к следующему кадру. Пишет их поток задачи через статические
    // хуки, читает — тот же поток в onStep, но volatile на случай, если хук
    // когда-нибудь позовут из Swing.
    private volatile String thoughtAction;
    private volatile String thoughtReason;
    private volatile List<UiaElement> seen;
    private volatile Integer chosen;

    /** Готовый кадр: JPEG с отметками и подпись к нему. */
    record Frame(int step, String caption, byte[] jpeg) {}

    private TaskReportRecorder(String taskId, String goal, Path dir, Robot robot) {
        this.taskId = taskId;
        this.goal = goal;
        this.dir = dir;
        this.robot = robot;
    }

    /** Папка всех отчётов. */
    static Path root() {
        return Paths.get(System.getProperty("user.home"), ".jarvis-agent", "reports");
    }

    /**
     * Начать отчёт новой задачи; null — отчёты выключены (report.enabled=false).
     *
     * @param robot чем снимать экран, если шаг пришёл без картинки (идёт живой стрим)
     */
    public static TaskReportRecorder start(String taskId, String goal, Robot robot) {
        if (!AgentSettings.flag("report.enabled", true)) return null;
        TaskReportRecorder r = new TaskReportRecorder(taskId, goal, root().resolve(safeName(taskId)), robot);
        try {
            r.frames.add(new Frame(0, "Задача: " + goal, ImageUtil.toJpeg(r.titleCard(), 0.8f)));
        } catch (Exception e) {
            // без титульного кадра отчёт всё равно полезен
        }
        current = r;
        return r;
    }

    // --- подсказки от экрана ПК (FaustOverlayController) ----------------------

    /** Модель решила, что сделать и почему, — подпись к следующему кадру. */
    static void noteThought(String action, String reason) {
        TaskReportRecorder r = current;
        if (r == null) return;
        r.thoughtAction = action;
        r.thoughtReason = reason;
    }

    /** Элементы окна, которые видит модель (режим UIA), — рамки на кадре. */
    static void noteSeen(List<UiaElement> elements) {
        TaskReportRecorder r = current;
        if (r != null) r.seen = elements == null ? null : List.copyOf(elements);
    }

    /** Модель выбрала элемент с этим номером — его рамка ярче остальных. */
    static void noteChosen(int index) {
        TaskReportRecorder r = current;
        if (r != null) r.chosen = index;
    }

    /** Действие выполнено, окно меняется — старые рамки к следующему кадру не относятся. */
    static void noteSeenDone() {
        TaskReportRecorder r = current;
        if (r == null) return;
        r.seen = null;
        r.chosen = null;
    }

    // --- кадры ------------------------------------------------------------------

    /**
     * Шаг агента. Контракт координат — как у StepReporter: при jpeg != null
     * цель (tx, ty) в пикселях этого jpeg, при jpeg == null — в пикселях экрана
     * (тогда кадр снимаем сами).
     *
     * @param phase «2/5» — какая подзадача плана идёт, или null
     */
    public void onStep(int step, String description, byte[] jpeg, Integer tx, Integer ty, String phase) {
        try {
            BufferedImage shot;
            double toFrame; // во сколько раз уменьшить координаты экрана до кадра
            Integer x = tx, y = ty;
            boolean fullScreen;
            if (jpeg != null) {
                shot = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (shot == null) return;
                java.awt.Dimension screen = screenSize(shot);
                toFrame = (double) shot.getWidth() / screen.width;
                // Кадр браузера — это окно страницы, а не весь экран: рамки UIA к
                // нему не подходят. Отличаем по пропорциям: у снимка экрана они как у экрана.
                fullScreen = Math.abs((double) shot.getHeight() / shot.getWidth()
                                      - (double) screen.height / screen.width) < 0.02;
            } else {
                if (robot == null) return;
                java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                shot = robot.createScreenCapture(new Rectangle(screen));
                toFrame = 1.0;
                fullScreen = true;
            }
            BufferedImage frame = ImageUtil.scaleDown(shot, FRAME_WIDTH);
            double k = (double) frame.getWidth() / shot.getWidth();
            if (x != null && y != null) {
                // jpeg != null: координаты уже в пикселях shot; иначе — в пикселях экрана = shot.
                x = (int) Math.round(x * k);
                y = (int) Math.round(y * k);
            }

            String[] lines = description == null ? new String[]{""} : description.split("\n", 2);
            String action = thoughtAction != null ? thoughtAction : CommandExecutor.describeStep(lines[0]);
            String reason = thoughtReason != null ? thoughtReason : lines.length > 1 ? lines[1] : null;
            String header = "Шаг " + step + (phase == null ? "" : " · подзадача " + phase)
                            + (lines[0].contains("из памяти") ? " · из памяти" : "");

            Graphics2D g = frame.createGraphics();
            try {
                smooth(g);
                List<UiaElement> elements = seen;
                if (fullScreen && elements != null) {
                    drawXray(g, elements, chosen, toFrame * k);
                }
                if (x != null && y != null) drawTarget(g, x, y);
                drawCaption(g, frame.getWidth(), frame.getHeight(), header, action, reason);
            } finally {
                g.dispose();
            }
            String caption = header + ": " + action + (reason == null || reason.isBlank() ? "" : " — " + reason);
            add(new Frame(step, caption, ImageUtil.toJpeg(frame, 0.75f)));
        } catch (Exception e) {
            // отчёт — не повод ронять задачу
        } finally {
            // Подсказки относились к этому шагу — к следующему они уже не подходят.
            thoughtAction = null;
            thoughtReason = null;
        }
    }

    /**
     * Задача закончилась: итоговый кадр, сохранение кадров и GIF на диск.
     *
     * @param summary короткий итог («цель достигнута за 7 шаг(ов), 48 с …»)
     */
    public void finish(boolean completed, String summary) {
        if (current == this) current = null;
        try {
            BufferedImage last;
            if (robot != null) {
                java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                last = ImageUtil.scaleDown(robot.createScreenCapture(new Rectangle(screen)), FRAME_WIDTH);
            } else {
                // Снимать нечем (тесты) — итог на пустом тёмном фоне, а не поверх титула.
                last = new BufferedImage(FRAME_WIDTH, FRAME_WIDTH * 9 / 16, BufferedImage.TYPE_INT_RGB);
                Graphics2D bg = last.createGraphics();
                bg.setColor(new Color(0x1E1817));
                bg.fillRect(0, 0, last.getWidth(), last.getHeight());
                bg.dispose();
            }
            Graphics2D g = last.createGraphics();
            try {
                smooth(g);
                drawVerdict(g, last.getWidth(), last.getHeight(), completed, summary);
            } finally {
                g.dispose();
            }
            frames.add(new Frame(-1, (completed ? "Итог: цель достигнута. " : "Итог: цель не достигнута. ") + summary,
                    ImageUtil.toJpeg(last, 0.8f)));
        } catch (Exception e) {
            // без итогового кадра тоже можно
        }
        try {
            save(completed);
            prune();
        } catch (Exception e) {
            System.err.println("не удалось сохранить видео-отчёт: " + e.getMessage());
        }
    }

    /**
     * Размер экрана, а без экрана (тесты, headless) — размер самого снимка:
     * тогда считаем, что снимок и есть весь экран.
     */
    private static java.awt.Dimension screenSize(BufferedImage fallback) {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize();
        } catch (java.awt.HeadlessException e) {
            return new java.awt.Dimension(fallback.getWidth(), fallback.getHeight());
        }
    }

    /** Сколько кадров набрано (для итога задачи). */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Добавить кадр, держась в пределах {@link #MAX_FRAMES}. Лишние убираем из
     * середины (не первый — титульный, а самый ранний из шагов): конец задачи
     * важнее начала — там видно, чем всё кончилось.
     */
    private void add(Frame f) {
        if (frames.size() >= MAX_FRAMES - 1 && frames.size() > 1) frames.remove(1);
        frames.add(f);
    }

    // --- диск ---------------------------------------------------------------------

    private void save(boolean completed) throws IOException {
        Files.createDirectories(dir);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("taskId", taskId);
        root.put("goal", goal);
        root.put("completed", completed);
        ArrayNode list = root.putArray("frames");
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            String file = String.format("frame-%03d.jpg", i);
            Files.write(dir.resolve(file), f.jpeg());
            list.addObject().put("step", f.step()).put("caption", f.caption()).put("file", file);
        }
        Files.writeString(dir.resolve("report.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        writeGif(frames, dir.resolve("report.gif"));
    }

    /** Оставить последние {@link #KEEP} отчётов. */
    private static void prune() throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) return;
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(TaskReportRecorder::modified).reversed())
                    .toList();
        }
        for (Path old : dirs.subList(Math.min(KEEP, dirs.size()), dirs.size())) {
            try (Stream<Path> files = Files.walk(old)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Отчёт задачи для телефона; пусто — такого отчёта нет (или он уже удалён). */
    public static Optional<ResultData.TaskReport> load(String taskId) {
        Path dir = root().resolve(safeName(taskId));
        Path json = dir.resolve("report.json");
        if (!Files.isRegularFile(json)) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json.toFile());
            List<ResultData.ReportFrame> out = new ArrayList<>();
            for (JsonNode f : root.path("frames")) {
                // Имя файла — только из нашего же списка и только внутри папки отчёта.
                Path file = dir.resolve(f.path("file").asText()).normalize();
                if (!file.startsWith(dir) || !Files.isRegularFile(file)) continue;
                out.add(new ResultData.ReportFrame(f.path("step").asInt(), f.path("caption").asText(),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(file))));
            }
            return Optional.of(new ResultData.TaskReport(taskId, root.path("goal").asText(),
                    root.path("completed").asBoolean(), out));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Готовая GIF-анимация задачи, если есть. */
    public static Optional<Path> gif(String taskId) {
        Path gif = root().resolve(safeName(taskId)).resolve("report.gif");
        return Files.isRegularFile(gif) ? Optional.of(gif) : Optional.empty();
    }

    /**
     * id задачи приходит с телефона — в имени папки оставляем только безопасные
     * символы, чтобы «..\..\» не вывел за пределы папки отчётов.
     */
    static String safeName(String taskId) {
        String s = taskId == null ? "" : taskId.replaceAll("[^A-Za-z0-9-]", "");
        return s.isEmpty() ? "unknown" : s.length() > 64 ? s.substring(0, 64) : s;
    }

    // --- GIF ----------------------------------------------------------------------

    /**
     * Склеить кадры в зацикленную GIF-анимацию стандартным ImageIO, без сторонних
     * библиотек. Титульный кадр и итог держатся дольше, шаги — по 1,6 с: столько
     * нужно, чтобы прочитать подпись из двух строк.
     */
    static void writeGif(List<Frame> frames, Path out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(Files.newOutputStream(out))) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            boolean first = true;
            for (Frame f : frames) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(f.jpeg()));
                if (img == null) continue;
                img = toRgb(ImageUtil.scaleDown(img, GIF_WIDTH));
                int delayCs = f.step() == 0 ? 250 : f.step() < 0 ? 400 : 160;
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB), param);
                configureFrame(meta, delayCs, first);
                writer.writeToSequence(new IIOImage(img, null, meta), param);
                first = false;
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /** Задержка кадра и — у первого — «повторять бесконечно» (расширение NETSCAPE2.0). */
    private static void configureFrame(IIOMetadata meta, int delayCs, boolean first) throws IOException {
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", String.valueOf(delayCs));
        gce.setAttribute("transparentColorIndex", "0");
        if (first) {
            IIOMetadataNode apps = child(root, "ApplicationExtensions");
            IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[]{1, 0, 0}); // 1 = счётчик повторов, 0 0 = бесконечно
            apps.appendChild(app);
        }
        meta.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    // --- рисование ------------------------------------------------------------------

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    /** Титульный кадр: что за задача и когда. */
    private BufferedImage titleCard() {
        BufferedImage img = new BufferedImage(FRAME_WIDTH, FRAME_WIDTH * 9 / 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            smooth(g);
            g.setColor(new Color(0x1E1817));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(COPPER);
            g.setFont(font(Font.BOLD, 30));
            g.drawString("Фауст · отчёт о задаче", 40, 80);
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 16));
            g.drawString(LocalDateTime.now().format(WHEN), 40, 110);
            g.setColor(TEXT);
            g.setFont(font(Font.PLAIN, 22));
            int y = 170;
            for (String line : wrap(g.getFontMetrics(), "«" + goal + "»", img.getWidth() - 80, 6)) {
                g.drawString(line, 40, y);
                y += 32;
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Рамки элементов окна, как их видит модель, и яркая — у выбранного. */
    private static void drawXray(Graphics2D g, List<UiaElement> elements, Integer chosen, double scale) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(ROSE.getRed(), ROSE.getGreen(), ROSE.getBlue(), 90));
        for (UiaElement e : elements) {
            g.drawRect((int) (e.x() * scale), (int) (e.y() * scale),
                    (int) (e.width() * scale), (int) (e.height() * scale));
        }
        if (chosen == null) return;
        for (UiaElement e : elements) {
            if (e.index() != chosen) continue;
            g.setStroke(new BasicStroke(3f));
            g.setColor(COPPER);
            g.drawRoundRect((int) (e.x() * scale) - 2, (int) (e.y() * scale) - 2,
                    (int) (e.width() * scale) + 4, (int) (e.height() * scale) + 4, 8, 8);
        }
    }

    /** Метка цели: кольцо с точкой — та же, что на телефоне. */
    private static void drawTarget(Graphics2D g, int x, int y) {
        g.setColor(new Color(COPPER.getRed(), COPPER.getGreen(), COPPER.getBlue(), 70));
        g.fillOval(x - 22, y - 22, 44, 44);
        g.setStroke(new BasicStroke(3f));
        g.setColor(COPPER);
        g.drawOval(x - 16, y - 16, 32, 32);
        g.fillOval(x - 4, y - 4, 8, 8);
    }

    /** Полоса снизу: «Шаг 3 · подзадача 2/5», действие и мысль модели. */
    private static void drawCaption(Graphics2D g, int w, int h, String header, String action, String reason) {
        g.setFont(font(Font.PLAIN, 16));
        FontMetrics fm = g.getFontMetrics();
        // Без эмодзи: Java2D рисует их пустыми квадратами — цветных шрифтов он не знает.
        List<String> why = reason == null || reason.isBlank() ? List.of() : wrap(fm, "Почему: " + reason, w - 32, 2);
        int band = 30 + 26 + why.size() * 22 + 10;
        g.setColor(BAND);
        g.fillRect(0, h - band, w, band);
        int y = h - band + 24;
        g.setColor(COPPER);
        g.setFont(font(Font.BOLD, 14));
        g.drawString(header, 16, y);
        y += 26;
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, 18));
        g.drawString(ellipsize(g.getFontMetrics(), action == null ? "" : action, w - 32), 16, y);
        g.setFont(font(Font.PLAIN, 16));
        g.setColor(MUTED);
        for (String line : why) {
            y += 22;
            g.drawString(line, 16, y);
        }
    }

    /** Итог задачи — крупно по центру итогового кадра. */
    private static void drawVerdict(Graphics2D g, int w, int h, boolean completed, String summary) {
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(0, 0, w, h);
        int boxW = w - 120, boxH = 150;
        int bx = 60, by = (h - boxH) / 2;
        g.setColor(BAND);
        g.fillRoundRect(bx, by, boxW, boxH, 24, 24);
        // Кружок рисуем сами, а не символом ✓: не во всех шрифтах Windows он есть.
        g.setColor(completed ? OK : FAIL);
        g.fillOval(bx + 28, by + 30, 22, 22);
        g.setFont(font(Font.BOLD, 28));
        g.drawString(completed ? "Цель достигнута" : "Цель не достигнута", bx + 62, by + 52);
        g.setColor(TEXT);
        g.setFont(font(Font.PLAIN, 16));
        int y = by + 86;
        for (String line : wrap(g.getFontMetrics(), summary == null ? "" : summary, boxW - 56, 3)) {
            g.drawString(line, bx + 28, y);
            y += 22;
        }
    }

    /** Разбить текст на строки по ширине; лишние строки — многоточием. */
    static List<String> wrap(FontMetrics fm, String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').split(" ")) {
            if (word.isEmpty()) continue;
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(tryLine) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(tryLine);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, ellipsize(fm, cut.get(maxLines - 1) + " …", width));
            return cut;
        }
        return out.stream().map(l -> ellipsize(fm, l, width)).toList();
    }

    private static String ellipsize(FontMetrics fm, String s, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
