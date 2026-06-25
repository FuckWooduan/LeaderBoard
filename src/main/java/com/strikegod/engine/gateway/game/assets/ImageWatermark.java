package com.strikegod.engine.gateway.game.assets;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 通用图片水印工具：把 {@code example.com} 以倾斜 + tile 方式压在原始图片上后再写盘。
 *
 * <p>水印强制走 Java2D 的 Logical font {@code SansSerif}（不指定 family），保证 ASCII 一定有
 * 字形可渲染——比起以前依赖某些中文字体回退，再不会出现「方框」豆腐块。
 *
 * <p>JPEG 不支持透明通道，输出前先把带 alpha 的 BufferedImage 平铺到白底上，再以 RGB 写出。
 * PNG 直接保留 ARGB。</p>
 *
 * <p>水印效果： -25° 倾斜，重复 tile，单字号 = min(w,h)/10，至少 28pt。每个字符同时画白色 3px
 * 描边 + 几乎黑色填充，无论亮底暗底都看得见。</p>
 *
 * <p><strong>边界完整性</strong>：每个 tile 在画前会把它的 4 个角投回到画布像素坐标系，
 * 凡是有任何一角落在画布外（导致字面被裁成 "Strik..." / "...keGod.COM"）的 tile 一律跳过 ——
 * 用户截图反馈过就是这个问题。tile 步距同时缩到字宽的 1.05 倍，保证内部覆盖密度足够，
 * 边界跳过若干个 tile 也不会留出空白区。</p>
 */
public final class ImageWatermark {

    /**
     * 水印文本：完整域名小写形式，方便用户截图后直接识别 / 输入网址。<br>
     * 之前用 "StrikeGod.COM"，但用户反馈在边缘会被裁成「Strik...」/「...keGod.COM」，
     * 改成 lowercase 的同时配合 {@link #drawWatermarkTiles} 内的边界裁切跳过逻辑，
     * 现在每个可见 tile 都能完整渲染整段 example.com。
     */
    private static final String WATERMARK_TEXT = "example.com";

    private ImageWatermark() {}

    /**
     * 给原始图片字节数组加水印。无法解码时直接返回原字节，调用方落盘行为不变。
     *
     * @param originalImageBytes 原始 PNG / JPEG 字节
     * @param formatHint         "png" / "jpg" / "jpeg"，决定输出编码（默认 png）
     */
    public static byte[] apply(byte[] originalImageBytes, String formatHint) throws IOException {
        if (originalImageBytes == null || originalImageBytes.length == 0) {
            return originalImageBytes;
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(originalImageBytes));
        if (src == null) {
            // 解码失败（可能是非标准 PNG / 太小）—— 直接透传原字节避免破坏管道
            return originalImageBytes;
        }

        boolean wantsJpeg =
                formatHint != null && (formatHint.equalsIgnoreCase("jpg") || formatHint.equalsIgnoreCase("jpeg"));

        // 水印画布固定使用 ARGB，方便描边 + 半透明字体合成
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, null);
            drawWatermarkTiles(g, src.getWidth(), src.getHeight());
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (wantsJpeg) {
            // JPEG 不支持 alpha；先平铺到白底再 encode
            BufferedImage flat = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D fg = flat.createGraphics();
            try {
                fg.setColor(Color.WHITE);
                fg.fillRect(0, 0, flat.getWidth(), flat.getHeight());
                fg.drawImage(out, 0, 0, null);
            } finally {
                fg.dispose();
            }
            ImageIO.write(flat, "jpg", baos);
        } else {
            ImageIO.write(out, "png", baos);
        }
        return baos.toByteArray();
    }

    /**
     * 给已经写入磁盘的图片就地加水印（读 → 加 → 覆盖）。任何环节失败都把原图保留不动。
     */
    public static void applyInPlace(Path imagePath) throws IOException {
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            return;
        }
        byte[] original = Files.readAllBytes(imagePath);
        String name = imagePath.getFileName().toString().toLowerCase();
        String fmt = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : "png";
        byte[] watermarked = apply(original, fmt);
        if (watermarked != null && watermarked != original) {
            Files.write(imagePath, watermarked);
        }
    }

    private static void drawWatermarkTiles(Graphics2D g, int width, int height) {
        // 不指定 family，让 Java2D 选 Logical font "SansSerif"——跨平台一定能渲染 ASCII。
        // 按【宽度】定字号（活动长图很高，用 min(w,h) 会让窄高图字号过大→平铺稀疏到几乎看不见）。
        int fontSize = Math.max(30, width / 24);
        Font baseFont = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        g.setFont(baseFont);
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D bounds = baseFont.getStringBounds(WATERMARK_TEXT, frc);
        float textW = (float) bounds.getWidth();
        float textH = (float) bounds.getHeight();

        AffineTransform original = g.getTransform();
        // 整画面旋转 -25°，再 tile 文字
        AffineTransform rotation = new AffineTransform(original);
        rotation.rotate(Math.toRadians(-25), width / 2.0, height / 2.0);
        g.setTransform(rotation);

        // 28% 不透明（在 40% 基础上减 30%）：能看清又不影响观感
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));

        // 步距贴近字宽：边界跳过几个 tile 后内部仍有重叠覆盖；之前 1.6f 的疏密下，
        // 边缘跳过 → 部分区域出现空白「水印免疫区」。
        float stepX = textW * 1.05f;
        float stepY = textH * 2.6f;
        int diag = (int) Math.ceil(Math.sqrt((double) width * width + (double) height * height));

        TextLayout tl = new TextLayout(WATERMARK_TEXT, baseFont, frc);
        Color outline = new Color(255, 255, 255, 200);
        Color fill = new Color(20, 20, 20, 220);
        BasicStroke stroke = new BasicStroke(3f);
        Rectangle canvas = new Rectangle(0, 0, width, height);

        for (float y = -diag; y < diag; y += stepY) {
            for (float x = -diag; x < diag; x += stepX) {
                // 仅当本 tile 的整个 bounding box（旋转后）完全落在画布像素范围内时才画。
                // 否则就是「字被画布边缘裁断」的那种半截 tile，直接跳过 —— 避免出现
                // "Strik..." / "...keGod.COM" 这类不完整水印。
                if (!tileFitsInsideCanvas(rotation, x, y, textW, textH, canvas)) {
                    continue;
                }
                Shape shape = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
                g.setStroke(stroke);
                g.setColor(outline);
                g.draw(shape);
                g.setColor(fill);
                g.fill(shape);
            }
        }
        g.setTransform(original);
    }

    /**
     * 把 tile 在「旋转后画布坐标系」中的 4 个角投影到原始像素坐标系，判断是否完全落在画布矩形内。
     * <p>tile 的本地 bounds：x 区间 [tx, tx+textW]，y 区间 [ty - textH, ty]（TextLayout 基线在 y）。
     */
    private static boolean tileFitsInsideCanvas(
            AffineTransform tx, float x, float y, float textW, float textH, Rectangle canvas) {
        Point2D.Double[] corners = new Point2D.Double[] {
            new Point2D.Double(x, y - textH),
            new Point2D.Double(x + textW, y - textH),
            new Point2D.Double(x + textW, y),
            new Point2D.Double(x, y)
        };
        for (var c : corners) {
            tx.transform(c, c);
            if (c.x < canvas.x || c.x > canvas.x + canvas.width || c.y < canvas.y || c.y > canvas.y + canvas.height) {
                return false;
            }
        }
        return true;
    }
}
