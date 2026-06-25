package com.strikegod.engine.gateway.game.activity.swf;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.commonshape.Matrix;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.timeline.Timeline;
import com.strikegod.engine.ffdec.jpexs.helpers.SerializableImage;
import java.awt.image.BufferedImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 click trigger 对应的"子页"渲染为 PNG。
 *
 * <p>新规则(用户要求):**子页独立成页**,不再叠在主舞台上。
 *
 * <ul>
 *   <li><strong>FRAME</strong>:{@code Target.frameNum} 非空 ── 跳到主时间轴第 N 帧渲染整张
 *   <li><strong>SPRITE</strong>:{@code Target.spriteId} 非空且 sprite 渲染出可见像素 ──
 *       渲染该 sprite 第 0 帧(独立画面,不叠主舞台)
 *   <li><strong>(被丢弃)</strong>:上述都不命中时返回 null;caller 直接跳过该 trigger,**不再** 在
 *       PDF 里产生"子页未识别"标注页。
 * </ul>
 */
@Slf4j
@Component
public class ClickFrameRenderer {

    /** SPRITE 检测"sprite 是否渲染出可见像素"的最小阈值。 */
    private static final int MIN_VISIBLE_PIXELS = 16;

    /** 子 sprite 在主舞台上的可见包围盒低于 5% 时,通常只是按钮 / 小图标,不应独立成页。 */
    private static final double MIN_SPRITE_STAGE_BOUNDS_RATIO = 0.05;

    /**
     * 渲染一张"点击后画面"。
     *
     * @return Result;命中失败时返回 null,caller 应跳过该 click trigger
     */
    public Result render(
            SwfDecoderFacade.ParseResult parsed, InteractionInferenceService.Trigger trigger, String triggerLabel) {
        SWF swf = parsed.swf();
        int canvasW = swf.displayRect.getWidth() / 20;
        int canvasH = swf.displayRect.getHeight() / 20;

        // 第一档:主时间轴跳帧
        if (trigger.target().frameNum() != null) {
            int frameIndex = Math.max(0, trigger.target().frameNum() - 1);
            BufferedImage img = renderTimelineFrame(swf.getTimeline(), frameIndex);
            String title = "点击 " + triggerLabel + " → 跳到第 " + (frameIndex + 1) + " 帧";
            log.debug("ClickFrameRenderer FRAME: {}", title);
            return new Result(img, title, Strategy.FRAME);
        }

        // 第二档:目标 sprite 独立成页(不叠主舞台)
        if (trigger.target().spriteId() != null) {
            CharacterTag tag = swf.getCharacter(trigger.target().spriteId());
            if (tag instanceof DefineSpriteTag sprite) {
                BufferedImage spriteImg = renderTimelineFrame(sprite.getTimeline(), 0);
                if (hasUsefulSpriteContent(spriteImg, canvasW, canvasH)) {
                    String title = "点击 " + triggerLabel + " (sprite#"
                            + trigger.target().spriteId() + ")";
                    log.debug("ClickFrameRenderer SPRITE: {}", title);
                    return new Result(spriteImg, title, Strategy.SPRITE);
                }
            }
        }

        // 命中失败 → null,caller 跳过(不再做"子页未识别"标注)
        log.debug("ClickFrameRenderer 命中失败 trigger={},跳过", triggerLabel);
        return null;
    }

    /** 用 ffdec frameToImageGet 渲染指定 timeline 第 N 帧(0-based)。 */
    private static BufferedImage renderTimelineFrame(Timeline timeline, int frameIndex) {
        SerializableImage img = SWF.frameToImageGet(
                timeline,
                frameIndex,
                0, // ratio
                null, // cursor point
                0, // mouseButton
                timeline.displayRect,
                Matrix.getScaleInstance(1.0),
                null, // colorTransform
                null, // backgroundColor
                1.0, // zoom
                false, // gfxOnly
                0 // selectedDepth
                );
        return img.getBufferedImage();
    }

    /**
     * 判断 sprite 是否值得单独成页。透明 / 空 sprite 直接丢;可见包围盒低于主舞台 5% 时也丢,避免把"点击兑换"这类按钮放到白纸中央。
     */
    private static boolean hasUsefulSpriteContent(BufferedImage img, int canvasW, int canvasH) {
        if (img == null || img.getWidth() < 4 || img.getHeight() < 4) {
            return false;
        }
        VisibleBounds bounds = measureVisibleBounds(img);
        if (bounds.visiblePixels() < MIN_VISIBLE_PIXELS) {
            return false;
        }
        int stageArea = Math.max(1, canvasW * canvasH);
        double stageBoundsRatio = bounds.area() / (double) stageArea;
        return stageBoundsRatio >= MIN_SPRITE_STAGE_BOUNDS_RATIO;
    }

    private static VisibleBounds measureVisibleBounds(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        int visible = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xff;
                if (alpha > 16) {
                    visible++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return new VisibleBounds(0, 0);
        }
        int area = (maxX - minX + 1) * (maxY - minY + 1);
        return new VisibleBounds(area, visible);
    }

    private record VisibleBounds(int area, int visiblePixels) {}

    /** 渲染结果:图像 + 策略标识 + 人类可读标题。 */
    public record Result(BufferedImage image, String title, Strategy strategy) {}

    /** 实际命中的渲染策略,便于上游分类统计 / 调试。 */
    public enum Strategy {
        /** Target.frameNum 命中 ── 主舞台跳帧 */
        FRAME,
        /** Target.spriteId 命中且 sprite 有可见像素 ── sprite 独立成页 */
        SPRITE
    }
}
