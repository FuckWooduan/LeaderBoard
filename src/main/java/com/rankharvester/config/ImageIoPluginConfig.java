package com.rankharvester.config;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 在 Spring Boot <b>fat jar</b> 里强制 ImageIO 重新扫描插件。
 *
 * <p>TwelveMonkeys(JPEG/TGA)、webp-imageio 等 ImageIO 插件靠 SPI（{@code META-INF/services}）自动注册。
 * 但 ImageIO 的 {@code IIORegistry} 在类初始化时用<b>系统 classloader</b> 扫描，看不到 fat jar 里嵌套的
 * {@code BOOT-INF/lib/*.jar}，导致插件未注册 → SWF 内嵌 JPEG/WebP 解不出 → 活动长图出现<b>黑框</b>、tips 不全。
 *
 * <p>这里用<b>当前线程 context classloader</b>（Spring Boot 的 LaunchedClassLoader，能看到嵌套 jar）调
 * {@link ImageIO#scanForPlugins()} 重扫，补注册这些 reader。原版引擎用 bootstrap 瘦 jar（扁平 classpath）故无此问题。
 */
@Configuration
public class ImageIoPluginConfig {

    private static final Logger log = LoggerFactory.getLogger(ImageIoPluginConfig.class);

    @PostConstruct
    void scan() {
        ImageIO.setUseCache(false);
        String before = String.join(",", ImageIO.getReaderFormatNames());
        long jpegBefore = jpegReaderCount();
        ImageIO.scanForPlugins(); // 用 context classloader 重扫，注册嵌套 jar 里的插件
        long jpegAfter = jpegReaderCount();
        log.info("[ImageIO] scanForPlugins 完成：JPEG reader {}→{} 个；可读格式: {}",
                jpegBefore, jpegAfter, String.join(",", ImageIO.getReaderFormatNames()));
        if (before.equals(String.join(",", ImageIO.getReaderFormatNames())) && jpegAfter <= 1) {
            log.warn("[ImageIO] 重扫后 JPEG reader 仍只有 JDK 自带 1 个——TwelveMonkeys 可能未在 classpath；长图内嵌 JPEG 可能仍黑框");
        }
    }

    private static long jpegReaderCount() {
        long n = 0;
        var it = ImageIO.getImageReadersByFormatName("jpeg");
        while (it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }
}
