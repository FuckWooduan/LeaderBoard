package com.rankharvester.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.gateway.game.activity.ActivityReportComposer;
import com.strikegod.engine.gateway.game.activity.ActivitySwfFetcher;
import com.strikegod.engine.gateway.game.activity.ActivityXmlMetadata;
import com.strikegod.engine.gateway.game.activity.swf.ActivityReportBuilder;
import com.strikegod.engine.gateway.game.activity.swf.InteractionInferenceService;
import com.strikegod.engine.gateway.game.activity.swf.SwfDecoderFacade;
import com.strikegod.engine.gateway.game.activity.swf.TooltipComposer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 活动「长图」渲染（移植自 StrikeGod 的 SWF→PNG 管线）。
 *
 * <p>给定一条活动({@link ActivityRow}) + res 上下文(resConfigXml / resurlBack，来自
 * {@link ActivityFetcher.FetchBundle})：定位并下载活动 SWF → 解密 → 内置 FFDEC 渲染 hover/click 合成 →
 * 输出 <b>report.png（长图）</b> + main.png + PDF。返回长图路径。
 */
@Service
public class ActivityImageRenderService {

    private static final Logger log = LoggerFactory.getLogger(ActivityImageRenderService.class);

    private final ActivitySwfFetcher swfFetcher;
    private final ActivityReportComposer composer;
    private final SwfDecoderFacade decoder;
    private final InteractionInferenceService inference;
    private final ObjectMapper json = new ObjectMapper();
    /** 长图/报告输出根目录（容器内挂载持久卷）。 */
    private final Path outputRoot;

    /**
     * 上次清 FFDEC 全局缓存的时刻（节流用）。FFDEC 的 {@link SWF} 解析会在<b>全局静态缓存</b>里堆 shape/image/script
     * 解码结果且<b>从不主动释放</b>——是后端 OOM（堆涨满 + 原生内存随之膨胀）的主因之一。每次渲染收尾时按节流清一次，
     * 避免高频浏览时 gc 风暴。
     */
    private static final java.util.concurrent.atomic.AtomicLong LAST_CACHE_CLEAR = new java.util.concurrent.atomic.AtomicLong();
    /** 两次全局缓存清理的最小间隔（毫秒）。 */
    private static final long CACHE_CLEAR_MIN_INTERVAL_MS = 30_000L;

    /** 渲染收尾：节流清 FFDEC 全局缓存（{@link SWF#clearAllStaticCache()} 含 Cache.clearAll + shape 缓存 + gc）。 */
    private static void clearFfdecCachesThrottled() {
        long now = System.currentTimeMillis();
        long last = LAST_CACHE_CLEAR.get();
        if (now - last >= CACHE_CLEAR_MIN_INTERVAL_MS && LAST_CACHE_CLEAR.compareAndSet(last, now)) {
            try {
                SWF.clearAllStaticCache();
            } catch (RuntimeException e) {
                log.debug("[活动渲染] 清 FFDEC 缓存失败（忽略）: {}", e.toString());
            }
        }
    }

    public ActivityImageRenderService(
            ActivitySwfFetcher swfFetcher,
            ActivityReportComposer composer,
            SwfDecoderFacade decoder,
            InteractionInferenceService inference,
            @Value("${rankharvester.activity.report-output-dir:data/activity}") String outputDir) {
        this.swfFetcher = swfFetcher;
        this.composer = composer;
        this.decoder = decoder;
        this.inference = inference;
        this.outputRoot = Path.of(outputDir);
    }

    /** 某活动长图(report.png)在磁盘上的路径（不保证已存在）。 */
    public Path reportPngPath(long activityId) {
        return outputRoot.resolve(String.valueOf(activityId)).resolve("report.png");
    }

    /** 某活动解密后 SWF 在磁盘上的路径（不保证已存在）。供前端 Ruffle 在线播放。 */
    public Path swfPath(long activityId) {
        return outputRoot.resolve(String.valueOf(activityId)).resolve("activity.swf");
    }

    /**
     * 仅下载并解密某活动的 SWF 落盘（不渲长图），供「SWF 在线播放」按需获取。
     * 成功返回 swf 路径；失败返回 {@code null}（已记日志，不抛）。
     */
    public Path fetchSwf(ActivityRow row, String resConfigXml, String resurlBack) {
        if (row.url() == null || row.url().isBlank()) {
            return null;
        }
        try {
            Path p = swfPath(row.activityId());
            if (java.nio.file.Files.exists(p)) {
                return p;
            }
            byte[] swf = swfFetcher.fetchAndDecrypt(toMetadata(row), resConfigXml, resurlBack);
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.write(p, swf);
            log.info("[活动SWF] activityId={} title={} swf={}（{} bytes）",
                    row.activityId(), row.title(), p.toAbsolutePath(), swf.length);
            return p;
        } catch (Exception e) {
            log.warn("[活动SWF] activityId={} 获取失败: {}", row.activityId(), e.toString());
            return null;
        }
    }

    /** 某活动「交互热区」JSON 缓存路径（不保证已存在）。供前端 Ruffle 播放器叠加精准 tooltip 层。 */
    public Path interactionsJsonPath(long activityId) {
        return outputRoot.resolve(String.valueOf(activityId)).resolve("interactions.json");
    }

    /** 某活动 hover 目标 sprite 渲染出的 tooltip 图（纯图形提示，无文本时用）。 */
    public Path tipPngPath(long activityId, int spriteId) {
        return outputRoot.resolve(String.valueOf(activityId)).resolve("tip-" + spriteId + ".png");
    }

    /**
     * 从已落盘的解密 SWF 静态推断 hover 热区（舞台坐标 + 准确 tooltip，与长图同源），写 interactions.json 缓存。
     * tooltip 内容三级取值：handler 字面量文本 → 目标 sprite 的可解释文本 → 目标 sprite 渲染成 PNG（img 字段）。
     * 成功返回 JSON 路径；SWF 缺失或解析失败返回 {@code null}（已记日志，不抛）。
     */
    public Path renderInteractionsJson(long activityId) {
        Path swf = swfPath(activityId);
        if (!Files.exists(swf)) {
            return null;
        }
        try {
            SwfDecoderFacade.ParseResult parsed = decoder.parse(Files.readAllBytes(swf));
            InteractionInferenceService.InferenceResult inferred = inference.infer(parsed);
            var items = new ArrayList<Map<String, Object>>();
            for (InteractionInferenceService.Trigger t : inferred.triggers()) {
                if (t.kind() != InteractionInferenceService.Trigger.Kind.HOVER) {
                    continue;
                }
                var b = t.bounds();
                if (b == null || b.width() <= 0 || b.height() <= 0) {
                    continue;
                }
                String text = cleanTooltip(t.target().tooltipText());
                String img = null;
                Integer sid = t.target().spriteId();
                if (text.isEmpty() && sid != null && sid > 0) {
                    text = spriteText(parsed, sid);
                    if (text.isEmpty() && exportTipPng(parsed, activityId, sid)) {
                        img = "/api/public/activity/" + activityId + "/tip/" + sid + ".png";
                    }
                }
                if (text.isEmpty() && img == null) {
                    continue;
                }
                var m = new LinkedHashMap<String, Object>();
                m.put("x", b.x());
                m.put("y", b.y());
                m.put("w", b.width());
                m.put("h", b.height());
                if (!text.isEmpty()) {
                    m.put("text", text);
                }
                if (img != null) {
                    m.put("img", img);
                }
                items.add(m);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("stageWidth", parsed.stageWidth());
            out.put("stageHeight", parsed.stageHeight());
            out.put("items", items);
            Path p = interactionsJsonPath(activityId);
            Files.createDirectories(p.getParent());
            Files.write(p, json.writeValueAsBytes(out));
            log.info("[活动交互] activityId={} 推断出 {} 个 hover 热区 → {}", activityId, items.size(), p.toAbsolutePath());
            return p;
        } catch (Exception e) {
            log.warn("[活动交互] activityId={} 推断失败: {}", activityId, e.toString());
            return null;
        } finally {
            clearFfdecCachesThrottled();
        }
    }

    /** 清洗 tooltip 文本；占位文案视为无文本。 */
    private static String cleanTooltip(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = TooltipComposer.cleanText(raw);
        return ("(hover 提示)".equals(text) || "hover 提示".equalsIgnoreCase(text)) ? "" : text;
    }

    /** 从 hover 目标 sprite 抽取可解释文本（与长图 DefineSprite 内容同源），抽不到返回空串。 */
    private String spriteText(SwfDecoderFacade.ParseResult parsed, int spriteId) {
        try {
            for (var c : decoder.extractDefineSpriteContents(parsed, java.util.Set.of(spriteId))) {
                String text = cleanTooltip(c.text());
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (Exception e) {
            log.debug("[活动交互] sprite#{} 文本抽取失败: {}", spriteId, e.toString());
        }
        return "";
    }

    /** 把 hover 目标 sprite 渲染成 tip-{sid}.png（纯图形提示）。@return 是否产出文件。 */
    private boolean exportTipPng(SwfDecoderFacade.ParseResult parsed, long activityId, int spriteId) {
        Path p = tipPngPath(activityId, spriteId);
        if (Files.exists(p)) {
            return true;
        }
        try {
            Files.createDirectories(p.getParent());
            try (var outStream = Files.newOutputStream(p)) {
                decoder.exportSpriteToPng(parsed, spriteId, outStream);
            }
            if (Files.size(p) > 0) {
                return true;
            }
            Files.deleteIfExists(p);
            return false;
        } catch (Exception e) {
            log.debug("[活动交互] sprite#{} 渲染 PNG 失败: {}", spriteId, e.toString());
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignore) {
                // 清理失败不影响主流程
            }
            return false;
        }
    }

    /** 把活动行映射为渲染管线所需的 XML 元数据。 */
    private static ActivityXmlMetadata toMetadata(ActivityRow row) {
        return new ActivityXmlMetadata(
                (int) row.activityId(),
                row.activityIds(),
                row.title(),
                row.url(),
                row.description(),
                row.startTime(),
                row.endTime(),
                Map.of());
    }

    /**
     * 渲染某活动的长图。成功返回 report.png 绝对路径；失败返回 {@code null}（已记日志，不抛）。
     */
    public Path renderReportPng(ActivityRow row, String resConfigXml, String resurlBack) {
        if (row.url() == null || row.url().isBlank()) {
            return null; // 无 SWF 资源，渲不出
        }
        try {
            ActivityXmlMetadata meta = toMetadata(row);
            byte[] swf = swfFetcher.fetchAndDecrypt(meta, resConfigXml, resurlBack);
            Path outDir = outputRoot.resolve(String.valueOf(row.activityId()));
            try { // 顺手把解密 SWF 落盘（前端 Ruffle 在线播放用），失败不影响长图
                java.nio.file.Files.createDirectories(outDir);
                java.nio.file.Files.write(outDir.resolve("activity.swf"), swf);
            } catch (Exception e) {
                log.warn("[活动SWF] activityId={} 落盘失败: {}", row.activityId(), e.toString());
            }
            ActivityReportBuilder.ReportPaths paths = composer.compose(swf, meta, outDir);
            Path png = paths.reportPng();
            if (png == null) {
                log.warn("[活动长图] activityId={} 渲染未产出 report.png", row.activityId());
                return null;
            }
            try { // 醒目水印：与网站同款 -25° 倾斜平铺 example.com（页脚不变，额外叠加）
                com.strikegod.engine.gateway.game.assets.ImageWatermark.applyInPlace(png);
            } catch (Exception e) {
                log.warn("[活动长图] activityId={} 加水印失败（不影响长图）: {}", row.activityId(), e.toString());
            }
            log.info("[活动长图] activityId={} title={} 长图={}", row.activityId(), row.title(), png.toAbsolutePath());
            return png.toAbsolutePath();
        } catch (Exception e) {
            log.warn("[活动长图] activityId={} 渲染失败: {}", row.activityId(), e.toString());
            return null;
        } finally {
            clearFfdecCachesThrottled();
        }
    }
}
