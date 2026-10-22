package com.jarvis.agent.vision;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Общие утилиты подготовки скриншота к отправке модели: и обычный
 * screenshot ({@code CommandExecutor}), и пиксельный computer-use агент
 * ({@link ScreenAgent}) уменьшают и сжимают картинку одинаково — код вынесен
 * сюда, чтобы не дублировать.
 */
public final class ImageUtil {

    private ImageUtil() {}

    /** Уменьшает картинку до maxW по ширине (пропорции сохраняются); если уже меньше — возвращает как есть. */
    public static BufferedImage scaleDown(BufferedImage src, int maxW) {
        if (src.getWidth() <= maxW) return src;
        int w = maxW;
        int h = src.getHeight() * maxW / src.getWidth();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** Кодирует картинку в JPEG с заданным качеством (0..1). */
    public static byte[] toJpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
        }
        writer.dispose();
        return out.toByteArray();
    }
}
