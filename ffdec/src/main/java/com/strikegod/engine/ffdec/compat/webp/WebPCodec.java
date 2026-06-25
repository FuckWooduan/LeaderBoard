package com.strikegod.engine.ffdec.compat.webp;

import com.strikegod.engine.ffdec.compat.webp.gif.GifToWebPConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Compatibility adapter for FFDEC's upstream WebP facade.
 *
 * <p>The original webp4j-core artifact is not published to Maven Central. This
 * adapter keeps FFDEC source compiling while delegating still-image WebP work to
 * the official ImageIO service provider on the module classpath.
 */
public final class WebPCodec {

    private WebPCodec() {}

    public static boolean isAvailable() {
        return ImageIO.getImageWritersByFormatName("webp").hasNext()
                && ImageIO.getImageReadersByFormatName("webp").hasNext();
    }

    public static BufferedImage decodeImage(byte[] data) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("WebP decoder is unavailable");
        }
        return image;
    }

    public static byte[] encodeImage(BufferedImage image, float quality) throws IOException {
        return writeStillImage(image);
    }

    public static byte[] encodeLosslessImage(BufferedImage image) throws IOException {
        return writeStillImage(image);
    }

    public static byte[] createAnimatedWebP(List<BufferedImage> images, int[] delays, GifToWebPConfig config)
            throws IOException {
        throw new IOException("Animated WebP export is not included in the StrikeGod Engine FFDEC build");
    }

    private static byte[] writeStillImage(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "webp", out)) {
            throw new IOException("WebP encoder is unavailable");
        }
        return out.toByteArray();
    }
}
