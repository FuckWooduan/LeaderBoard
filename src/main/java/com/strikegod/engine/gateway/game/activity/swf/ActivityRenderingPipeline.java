package com.strikegod.engine.gateway.game.activity.swf;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SwfOpenException;
import com.strikegod.engine.gateway.game.activity.ActivityXmlMetadata;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 端到端渲染管线 (纯 ffdec 路线,输出主页 + 可解释 DefineSprite 内容)。
 *
 * <pre>
 *   SWF bytes
 *      │
 *      ▼  SwfDecoderFacade.parse
 *   ParseResult
 *      │
 *      ├── inference.infer → triggers + entryFrame + decompiledSources
 *      ├── 从 triggers 计算 suppressSet 和 click 可见状态组
 *      ├── analyzeRootPages:定位页面型 DefineSprite,渲染 sprite 本体为视觉子页
 *      ├── 主帧 (干净版): renderMainFrameSuppressing(parsed, entryFrame-1, suppressSet)
 *      └── 全量扫描 DefineSprite:抽 DefineEditText 自绘文本,页面型内容保留原图
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityRenderingPipeline {

    private static final int TWIPS_PER_PIXEL = 20;
    private static final AtomicBoolean INTERACTION_INFERENCE_WARNING_LOGGED = new AtomicBoolean();

    private final SwfDecoderFacade decoder;
    private final InteractionInferenceService inference;
    private final TooltipComposer tooltipComposer;
    private final ClickFrameRenderer clickRenderer;

    public RenderingResult render(byte[] swfBytes, ActivityXmlMetadata metadata) throws IOException, SwfOpenException {
        if (metadata == null) {
            throw new IllegalArgumentException("活动 SWF 解析必须携带 activityList.xml 元数据");
        }
        SwfDecoderFacade.ParseResult parsed = decoder.parse(swfBytes);
        SWF swf = parsed.swf();
        int canvasW = swf.displayRect.getWidth() / TWIPS_PER_PIXEL;
        int canvasH = swf.displayRect.getHeight() / TWIPS_PER_PIXEL;

        List<String> warnings = new ArrayList<>();
        InteractionInferenceService.InferenceResult inferred = inferInteractionsSafely(parsed, metadata, warnings);
        int entryFrameIndex = Math.max(0, inferred.entryFrame() - 1);

        // 收集所有 Target.spriteId 作为 suppress set ── 这些是动态 visible=true 的 DefineSprite,
        // 默认就该被 AS 隐藏,不该在主帧里露出。
        Set<Integer> suppressSet = new HashSet<>();
        suppressSet.addAll(inferred.initiallyHiddenSpriteIds());
        Set<Integer> passiveControlSpriteIds = collectPassiveControlSpriteIds(inferred.triggers());
        Set<Integer> defineSpriteCandidateIds = new HashSet<>();
        Set<Integer> activityDetailSpriteIds = new HashSet<>();
        List<Set<Integer>> visualStateGroups = new ArrayList<>();
        for (var trigger : inferred.triggers()) {
            Integer spriteId = trigger.target().spriteId();
            if (spriteId != null && spriteId > 0) {
                suppressSet.add(spriteId);
                if (trigger.kind() == InteractionInferenceService.Trigger.Kind.HOVER) {
                    defineSpriteCandidateIds.add(spriteId);
                    activityDetailSpriteIds.add(spriteId);
                }
            }
            Set<Integer> visualStateGroup = new HashSet<>();
            if (spriteId != null && spriteId > 0) {
                visualStateGroup.add(spriteId);
            }
            for (Integer suppressSpriteId : trigger.target().suppressSpriteIds()) {
                if (suppressSpriteId != null && suppressSpriteId > 0) {
                    suppressSet.add(suppressSpriteId);
                    if (!passiveControlSpriteIds.contains(suppressSpriteId)) {
                        visualStateGroup.add(suppressSpriteId);
                    }
                    if (trigger.kind() == InteractionInferenceService.Trigger.Kind.HOVER) {
                        defineSpriteCandidateIds.add(suppressSpriteId);
                        activityDetailSpriteIds.add(suppressSpriteId);
                    }
                }
            }
            if (trigger.kind() == InteractionInferenceService.Trigger.Kind.CLICK && visualStateGroup.size() > 1) {
                visualStateGroups.add(visualStateGroup);
            }
        }

        SwfDecoderFacade.RootPageAnalysis rootPages =
                decoder.analyzeRootPages(parsed, entryFrameIndex, suppressSet, visualStateGroups);
        activityDetailSpriteIds.addAll(rootPages.activityDetailSpriteIds());
        suppressSet.removeAll(rootPages.unsuppressedSpriteIds());
        defineSpriteCandidateIds.removeAll(rootPages.absorbedSpriteIds());
        defineSpriteCandidateIds.removeAll(activityDetailSpriteIds);
        Set<Integer> visibleMainSpriteIds = decoder.collectVisibleMainSpriteIds(parsed, entryFrameIndex, suppressSet);
        SwfDecoderFacade.MainFrameTextExtraction mainFrameTexts =
                decoder.extractVisibleMainSelfRenderTexts(parsed, entryFrameIndex, suppressSet);
        suppressSet.addAll(mainFrameTexts.suppressCharacterIds());

        BufferedImage mainFrame = decoder.renderMainFrameSuppressing(parsed, entryFrameIndex, suppressSet);
        Set<Integer> excludedSpriteIds = new HashSet<>(rootPages.absorbedSpriteIds());
        excludedSpriteIds.addAll(visibleMainSpriteIds);
        List<SwfDecoderFacade.DefineSpriteContent> defineSprites = new ArrayList<>(mainFrameTexts.contents());
        defineSprites.addAll(decoder.extractDefineSpriteContents(parsed, defineSpriteCandidateIds, excludedSpriteIds));
        defineSprites.addAll(decoder.extractActivityDetailSpriteContents(parsed, activityDetailSpriteIds, Set.of()));

        Set<String> visibleMainTextSignatures =
                decoder.extractVisibleMainTexts(parsed, entryFrameIndex, suppressSet).stream()
                        .map(ActivityRenderingPipeline::textSignature)
                        .filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toSet());

        List<RenderedHoverSmall> hoverSmall = new ArrayList<>();
        List<RenderedHoverPage> hoverMedium = new ArrayList<>();
        List<RenderedHoverPage> hoverLarge = new ArrayList<>();
        List<RenderedClickFrame> clickFrames = new ArrayList<>();
        // 去重:click 按 (frameNum / target.spriteId / trigger.spriteId) 三元组,hover 按 cleanedText。
        // 历史兼容:这些列表不再进入 PDF 主结构,最终报告使用全量扫描出的 defineSprites 单页排版。
        Set<String> seenClickKeys = new HashSet<>();
        Set<String> seenHoverTexts = new HashSet<>();

        for (InteractionInferenceService.Trigger trigger : inferred.triggers()) {
            try {
                if (trigger.kind() == InteractionInferenceService.Trigger.Kind.HOVER) {
                    String text = pickHoverText(trigger);
                    String cleaned = TooltipComposer.cleanText(text);
                    if (cleaned.isEmpty()) {
                        continue;
                    }
                    if (isPlaceholderHoverText(cleaned)) {
                        continue;
                    }
                    if (visibleMainTextSignatures.contains(textSignature(cleaned))) {
                        continue;
                    }
                    if (!seenHoverTexts.add(cleaned)) {
                        continue;
                    }
                    TooltipComposer.Size size = TooltipComposer.classify(cleaned);
                    switch (size) {
                        case SMALL -> {
                            // 小型 hover 只会让 PDF 多一张"主页 + 一小块提示"的重复页,这里直接跳过。
                        }
                        case MEDIUM -> {
                            BufferedImage page = tooltipComposer.composeStandalone(canvasW, canvasH, cleaned);
                            hoverMedium.add(new RenderedHoverPage(trigger, page, cleaned));
                        }
                        case LARGE -> {
                            BufferedImage page = tooltipComposer.composeStandalone(canvasW, canvasH, cleaned);
                            hoverLarge.add(new RenderedHoverPage(trigger, page, cleaned));
                        }
                    }
                } else if (trigger.kind() == InteractionInferenceService.Trigger.Kind.CLICK) {
                    String dedupKey = clickDedupKey(trigger);
                    if (!seenClickKeys.add(dedupKey)) {
                        continue;
                    }
                    String label = "trigger#" + trigger.spriteId();
                    ClickFrameRenderer.Result result = clickRenderer.render(parsed, trigger, label);
                    if (result == null) {
                        // 命中失败 → 直接丢弃,不在 PDF 里产生标注页
                        continue;
                    }
                    if (isRedundantFrameClick(mainFrame, result)) {
                        continue;
                    }
                    clickFrames.add(new RenderedClickFrame(trigger, result));
                }
            } catch (Exception e) {
                warnings.add("trigger#" + trigger.spriteId() + " " + trigger.kind() + " 渲染失败: " + e.getMessage());
                log.warn("trigger 渲染失败: {} {}", trigger.kind(), e.getMessage(), e);
            }
        }

        log.info(
                "ActivityRenderingPipeline 完成: canvas={}x{} entry={} triggers={} visualPage={} defineSprite={} click={} hoverS={} hoverM={} hoverL={} warnings={}",
                canvasW,
                canvasH,
                inferred.entryFrame(),
                inferred.triggers().size(),
                rootPages.visualPages().size(),
                defineSprites.size(),
                clickFrames.size(),
                hoverSmall.size(),
                hoverMedium.size(),
                hoverLarge.size(),
                warnings.size());
        return new RenderingResult(
                parsed,
                metadata,
                mainFrame,
                rootPages.visualPages(),
                defineSprites,
                clickFrames,
                hoverSmall,
                hoverMedium,
                hoverLarge,
                warnings);
    }

    private InteractionInferenceService.InferenceResult inferInteractionsSafely(
            SwfDecoderFacade.ParseResult parsed, ActivityXmlMetadata metadata, List<String> warnings) {
        try {
            return inference.infer(parsed);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            warnings.add("AS3 静态交互推断失败，已降级为主帧/DefineSprite 扫描: " + message);
            if (INTERACTION_INFERENCE_WARNING_LOGGED.compareAndSet(false, true)) {
                log.warn(
                        "AS3 静态交互推断失败，活动仍继续解析；后续同类异常仅写 debug。activityId={} title={} cause={}",
                        metadata.activityId(),
                        metadata.title(),
                        message);
            }
            log.debug("AS3 静态交互推断异常堆栈 activityId={}", metadata.activityId(), e);
            return new InteractionInferenceService.InferenceResult(List.of(), Map.of(), 1, Set.of());
        }
    }

    private static String pickHoverText(InteractionInferenceService.Trigger trigger) {
        String triggerText = trigger.target().tooltipText();
        if (triggerText != null && !triggerText.isBlank()) {
            return triggerText;
        }
        return "";
    }

    private static boolean isPlaceholderHoverText(String text) {
        return "(hover 提示)".equals(text) || "hover 提示".equalsIgnoreCase(text);
    }

    private static Set<Integer> collectPassiveControlSpriteIds(List<InteractionInferenceService.Trigger> triggers) {
        Set<Integer> ids = new HashSet<>();
        for (InteractionInferenceService.Trigger trigger : triggers) {
            if (trigger.kind() != InteractionInferenceService.Trigger.Kind.CLICK) {
                continue;
            }
            InteractionInferenceService.Target target = trigger.target();
            if (target.spriteId() == null
                    && target.frameNum() == null
                    && (target.tooltipText() == null || target.tooltipText().isBlank())
                    && target.suppressSpriteIds().isEmpty()) {
                ids.add(trigger.spriteId());
            }
        }
        return ids;
    }

    private static String textSignature(String text) {
        return TooltipComposer.cleanText(text).replaceAll("\\s+", "");
    }

    /**
     * click trigger 去重 key:优先按 frameNum,其次按 target.spriteId,最后按 trigger 自身 spriteId。
     * target 为空的 trigger 后续会被 ClickFrameRenderer 丢弃,这里只保证同一个 trigger 不重复尝试。
     */
    private static String clickDedupKey(InteractionInferenceService.Trigger trigger) {
        Integer frame = trigger.target().frameNum();
        if (frame != null) {
            return "F:" + frame;
        }
        Integer spriteId = trigger.target().spriteId();
        if (spriteId != null) {
            return "S:" + spriteId;
        }
        return "T:" + trigger.spriteId();
    }

    /**
     * 主时间轴跳帧有时只是原版提示层露出来,画面主体没有变化。差异过小的 FRAME click 页直接丢弃,
     * 避免后续误把"主页再来一遍 + 一个旧提示层"当作有效内容。
     */
    private static boolean isRedundantFrameClick(BufferedImage mainFrame, ClickFrameRenderer.Result result) {
        if (result.strategy() != ClickFrameRenderer.Strategy.FRAME) {
            return false;
        }
        BufferedImage img = result.image();
        if (mainFrame.getWidth() != img.getWidth() || mainFrame.getHeight() != img.getHeight()) {
            return false;
        }

        int width = img.getWidth();
        int height = img.getHeight();
        int total = width * height;
        int sampleStep = Math.max(1, (int) Math.sqrt(total / 20000.0));
        int changed = 0;
        int sampled = 0;
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y += sampleStep) {
            for (int x = 0; x < width; x += sampleStep) {
                sampled++;
                int a = mainFrame.getRGB(x, y);
                int b = img.getRGB(x, y);
                if (pixelDistance(a, b) > 35) {
                    changed++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (changed == 0) {
            return true;
        }
        double changedRatio = changed / (double) Math.max(1, sampled);
        int bboxW = maxX - minX + 1;
        int bboxH = maxY - minY + 1;
        double changedBoundsRatio = bboxW * bboxH / (double) total;
        return changedRatio < 0.12 && changedBoundsRatio < 0.20;
    }

    private static int pixelDistance(int a, int b) {
        int ar = (a >>> 16) & 0xff;
        int ag = (a >>> 8) & 0xff;
        int ab = a & 0xff;
        int br = (b >>> 16) & 0xff;
        int bg = (b >>> 8) & 0xff;
        int bb = b & 0xff;
        return Math.max(Math.abs(ar - br), Math.max(Math.abs(ag - bg), Math.abs(ab - bb)));
    }

    /**
     * 一次活动 SWF 渲染的全部产出。
     *
     * <p>**新结构**(用户要求):main 干净不含原版动态层;可解释 DefineSprite 抽取为内容块;PDF 侧单页排版。
     */
    public record RenderingResult(
            SwfDecoderFacade.ParseResult parsed,
            ActivityXmlMetadata metadata,
            BufferedImage mainFrame,
            List<SwfDecoderFacade.VisualPageContent> visualPages,
            List<SwfDecoderFacade.DefineSpriteContent> defineSprites,
            List<RenderedClickFrame> clickFrames,
            List<RenderedHoverSmall> hoverSmall,
            List<RenderedHoverPage> hoverMedium,
            List<RenderedHoverPage> hoverLarge,
            List<String> warnings) {}

    /** 历史兼容字段:小型 hover 文本页当前默认跳过。 */
    public record RenderedHoverSmall(InteractionInferenceService.Trigger trigger, BufferedImage overlay, String text) {}

    /** 历史兼容字段:中 / 大型 hover 文本页当前不再作为 PDF 主结构。 */
    public record RenderedHoverPage(InteractionInferenceService.Trigger trigger, BufferedImage page, String text) {}

    /** 历史兼容字段:单个 click trigger 渲染出的子页面图。 */
    public record RenderedClickFrame(InteractionInferenceService.Trigger trigger, ClickFrameRenderer.Result result) {}
}
