package com.strikegod.engine.ffdec.compat.webp.gif;

/** Minimal compatibility value object for FFDEC's optional animated WebP path. */
public final class GifToWebPConfig {

    private GifToWebPConfig() {}

    public static GifToWebPConfig createLosslessConfig() {
        return new GifToWebPConfig();
    }
}
