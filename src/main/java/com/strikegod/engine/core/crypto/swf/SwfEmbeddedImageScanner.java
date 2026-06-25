package com.strikegod.engine.core.crypto.swf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 从 SWF 影片二进制中扫描 JPEG / PNG 魔数并尝试解码位图（启发式，无需完整标签解析）。
 *
 * <p>
 * 用于不依赖完整 SWF 标签解析的图片兜底提取。
 */
public final class SwfEmbeddedImageScanner {

    private SwfEmbeddedImageScanner() {}

    /**
     * 扫描影片字节中所有可解码的 JPEG / PNG 图片。
     */
    public static List<FoundImage> scanAll(byte[] movie) {
        List<FoundImage> list = new ArrayList<>();
        for (int i = 0; i < movie.length - 3; i++) {
            if (isPng(movie, i)) {
                BufferedImage img = tryDecode(movie, i);
                if (img != null) {
                    list.add(new FoundImage("png", img, i));
                }
            } else if (isJpeg(movie, i)) {
                BufferedImage img = tryDecode(movie, i);
                if (img != null) {
                    list.add(new FoundImage("jpeg", img, i));
                }
            }
        }
        return list;
    }

    /**
     * 返回影片字节中第一张可解码的 JPEG / PNG 图片，找不到返回 null。
     */
    public static FoundImage first(byte[] movie) {
        for (int i = 0; i < movie.length - 3; i++) {
            if (isPng(movie, i) || isJpeg(movie, i)) {
                BufferedImage img = tryDecode(movie, i);
                if (img != null) {
                    String fmt = isPng(movie, i) ? "png" : "jpeg";
                    return new FoundImage(fmt, img, i);
                }
            }
        }
        return null;
    }

    private static boolean isJpeg(byte[] b, int i) {
        return (b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b, int i) {
        return (b[i] & 0xFF) == 0x89 && b[i + 1] == 0x50 && b[i + 2] == 0x4E && b[i + 3] == 0x47;
    }

    private static BufferedImage tryDecode(byte[] movie, int offset) {
        int maxSlice = Math.min(movie.length - offset, 16 * 1024 * 1024);
        byte[] slice = new byte[maxSlice];
        System.arraycopy(movie, offset, slice, 0, maxSlice);
        for (int len = Math.min(maxSlice, 512 * 1024); len >= 64; len -= 256) {
            try {
                return ImageIO.read(new ByteArrayInputStream(slice, 0, len));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 扫描到的图片信息。
     *
     * @param format 图片格式（"png" 或 "jpeg"）
     * @param image  解码后的位图
     * @param offset 在影片字节中的起始偏移
     */
    public record FoundImage(String format, BufferedImage image, int offset) {}
}
