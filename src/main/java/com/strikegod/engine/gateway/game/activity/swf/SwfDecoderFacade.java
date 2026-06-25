package com.strikegod.engine.gateway.game.activity.swf;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.AbortRetryIgnoreHandler;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.ReadOnlyTagList;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SwfOpenException;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.commonshape.Matrix;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.modes.ScriptExportMode;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.settings.ScriptExportSettings;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.DefineEditTextTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.PlaceObject2Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.PlaceObjectTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.PlaceObjectTypeTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.timeline.Timeline;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.MATRIX;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.RECT;
import com.strikegod.engine.ffdec.jpexs.helpers.SerializableImage;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Engine 端 内置 FFDEC 的薄封装。
 *
 * <p>把 ffdec 的 API 收敛到几个稳定签名，升级 ffdec 时只改本类的实现而非散落的调用方。
 */
@Slf4j
@Service
public class SwfDecoderFacade {

    private static final int SELF_RENDER_TEXT_MIN_LENGTH = 15;
    private static final Pattern EMBED_SYMBOL_PATTERN = Pattern.compile("symbol\\s*=\\s*\"symbol(\\d+)\"");
    private static final Pattern ADD_FRAME_SCRIPT_PATTERN =
            Pattern.compile("addFrameScript\\((\\d+)\\s*,\\s*this\\.([A-Za-z_$][\\w$]*)\\)");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final List<RootPageRenderStrategy> ROOT_PAGE_RENDER_STRATEGIES =
            List.of(new RootTimelineVisualStateStrategy(), new SelfContainedPopupSpriteStrategy());

    /**
     * 解析 SWF 字节流为 ffdec 内部模型。
     *
     * @param swfBytes 已解密、未压缩 SWF 原始字节流（首字节通常是 'F' / 'C' / 'Z'）
     * @throws SwfOpenException SWF 头部不合法
     * @throws IOException 读取被中断或字节流损坏
     */
    public ParseResult parse(byte[] swfBytes) throws SwfOpenException, IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(swfBytes)) {
            SWF swf = new SWF(in, true);
            ReadOnlyTagList tags = swf.getTags();
            log.debug("SWF parsed: version={} frameCount={} tags={}", swf.version, swf.frameCount, tags.size());
            return new ParseResult(swf, tags);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SWF 解析被中断", e);
        }
    }

    /**
     * 渲染主舞台第 N 帧 PNG,但**临时屏蔽** {@code suppressCharacterIds} 中列出的 sprite。
     *
     * <p>用途:活动 SWF 在主时间轴 frame 0/N 把 hover/click tooltip widgets 一同放置, 然后用 AS3 在 init 中
     * {@code visible=false} 隐藏。ffdec {@link SWF#frameToImageGet} 不执行 AS,所以默认渲染会带上这些 tooltip。
     * 本方法在渲染前把对应 PlaceObject2Tag 的 {@code placeFlagHasCharacter} 临时置 false, 渲染完后再还原,
     * 不影响后续渲染调用。
     *
     * <p>仅作用于主时间轴顶层 PlaceObject(以及 DefineSprite 子时间轴里的 PlaceObject), 不递归再深一层。
     *
     * @param parsed parse 出来的 SWF
     * @param frameIndex 主时间轴帧号 (0-based)
     * @param suppressCharacterIds 要隐藏的 character id 集合(典型来源:`Trigger.target.spriteId` 集合)
     */
    public BufferedImage renderMainFrameSuppressing(
            ParseResult parsed, int frameIndex, Set<Integer> suppressCharacterIds) {
        SWF swf = parsed.swf();
        List<MutedPlaceObject> mutated = new ArrayList<>();
        List<ShiftedPlaceObject> shifted = new ArrayList<>();
        try {
            Set<Integer> suppress = suppressCharacterIds == null ? Set.of() : suppressCharacterIds;
            Map<Integer, Integer> scriptStoppedFrames = extractScriptStoppedSpriteFrames(parsed);
            List<ScriptStoppedSpriteOverlay> overlays = collectAndSuppressScriptStoppedSpriteOverlays(
                    swf, swf.getTags(), frameIndex, suppress, scriptStoppedFrames, mutated);
            collectAndSuppress(swf.getTags(), suppress, mutated);
            collectAndShiftEditTextBounds(swf, swf.getTags(), shifted);
            for (CharacterTag ch : swf.getCharacters(false).values()) {
                if (ch instanceof DefineSpriteTag spr) {
                    collectAndSuppress(spr.getTags(), suppress, mutated);
                    collectAndShiftEditTextBounds(swf, spr.getTags(), shifted);
                }
            }
            resetAllTimelines(swf);
            return drawScriptStoppedSpriteOverlays(renderMainFrameAt(swf, frameIndex), overlays);
        } finally {
            for (ShiftedPlaceObject p : shifted) {
                p.restore();
            }
            for (MutedPlaceObject p : mutated) {
                p.restore();
            }
            resetAllTimelines(swf);
        }
    }

    /**
     * 从 ffdec 反编译出的真实 AS3 源码识别“播放到某帧后 stop()”的 sprite。
     *
     * <p>这些 sprite 在 Flash Player 中会从第 1 帧播放到脚本声明的 stop 帧；ffdec 静态主帧渲染不会执行 AS3，
     * 因而会停在第 1 帧。这里不按文件名、character id 或可见面积猜测，只接受源码中明确出现的：
     *
     * <pre>
     * [Embed(..., symbol="symbol13")]
     * addFrameScript(18, this.frame19);
     * function frame19() { stop(); }
     * </pre>
     */
    private Map<Integer, Integer> extractScriptStoppedSpriteFrames(ParseResult parsed) {
        Map<String, String> sources;
        try {
            sources = decompileAs3(parsed);
        } catch (Exception e) {
            log.debug("AS3 反编译失败，跳过脚本 stop 帧推进: {}", e.getMessage());
            return Map.of();
        }
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (String source : sources.values()) {
            Matcher symbolMatcher = EMBED_SYMBOL_PATTERN.matcher(source);
            if (!symbolMatcher.find()) {
                continue;
            }
            int symbolId = Integer.parseInt(symbolMatcher.group(1));
            Map<String, Integer> frameScripts = new LinkedHashMap<>();
            Matcher frameMatcher = ADD_FRAME_SCRIPT_PATTERN.matcher(source);
            while (frameMatcher.find()) {
                frameScripts.put(frameMatcher.group(2), Integer.parseInt(frameMatcher.group(1)));
            }
            if (frameScripts.isEmpty()) {
                continue;
            }
            Map<String, String> bodies = extractFunctionBodies(source);
            for (Map.Entry<String, Integer> frameScript : frameScripts.entrySet()) {
                String body = bodies.get(frameScript.getKey());
                if (body != null && body.matches("(?s).*\\bstop\\s*\\(\\s*\\)\\s*;?.*")) {
                    result.merge(symbolId, frameScript.getValue(), Math::max);
                }
            }
        }
        return result;
    }

    private static Map<String, String> extractFunctionBodies(String source) {
        List<FunctionStart> starts = new ArrayList<>();
        Matcher matcher = FUNCTION_PATTERN.matcher(source);
        while (matcher.find()) {
            starts.add(new FunctionStart(matcher.group(1), matcher.start()));
        }
        Map<String, String> bodies = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            FunctionStart start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1).offset() : source.length();
            bodies.put(start.name(), source.substring(start.offset(), end));
        }
        return bodies;
    }

    private static BufferedImage renderMainFrameAt(SWF swf, int frameIndex) {
        Timeline t = swf.getTimeline();
        int safeFrame = Math.max(0, Math.min(frameIndex, t.getFrameCount() - 1));
        SerializableImage img = SWF.frameToImageGet(
                t, safeFrame, 0, null, 0, t.displayRect, Matrix.getScaleInstance(1.0), null, null, 1.0, false, 0);
        return img.getBufferedImage();
    }

    private static void collectAndSuppress(
            ReadOnlyTagList tags, Set<Integer> suppress, List<MutedPlaceObject> mutated) {
        if (suppress == null || suppress.isEmpty()) {
            return;
        }
        for (Tag tag : tags) {
            if (tag instanceof PlaceObject2Tag p2 && p2.placeFlagHasCharacter && suppress.contains(p2.characterId)) {
                mutated.add(MutedPlaceObject.from(p2));
                p2.placeFlagHasCharacter = false;
            } else if (tag instanceof PlaceObjectTag p1 && suppress.contains(p1.getCharacterId())) {
                mutated.add(MutedPlaceObject.from(p1));
                p1.setMatrix(offstageMatrix(p1.getMatrix()));
            }
        }
    }

    private static List<ScriptStoppedSpriteOverlay> collectAndSuppressScriptStoppedSpriteOverlays(
            SWF swf,
            ReadOnlyTagList tags,
            int frameIndex,
            Set<Integer> suppress,
            Map<Integer, Integer> scriptStoppedFrames,
            List<MutedPlaceObject> mutated) {
        if (scriptStoppedFrames == null || scriptStoppedFrames.isEmpty()) {
            return List.of();
        }
        List<ScriptStoppedSpriteOverlay> overlays = new ArrayList<>();
        int currentFrame = 0;
        for (Tag tag : tags) {
            if (tag instanceof com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.ShowFrameTag) {
                if (currentFrame >= frameIndex) {
                    break;
                }
                currentFrame++;
                continue;
            }
            if (currentFrame != frameIndex || !(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || suppress.contains(childId)) {
                continue;
            }
            CharacterTag child = swf.getCharacter(childId);
            if (!(child instanceof DefineSpriteTag sprite)) {
                continue;
            }
            Integer stopFrame = scriptStoppedFrames.get(childId);
            if (stopFrame == null || stopFrame <= 0 || stopFrame >= sprite.getFrameCount()) {
                continue;
            }
            BufferedImage stoppedFrame = containsSuppressedDescendant(swf, sprite, suppress, new HashSet<>())
                    ? renderSpriteFrameSuppressing(swf, sprite, suppress, stopFrame)
                    : renderTimelineFrame(sprite.getTimeline(), stopFrame);
            if (tag instanceof PlaceObject2Tag p2) {
                mutated.add(MutedPlaceObject.from(p2));
                p2.placeFlagHasCharacter = false;
            } else if (tag instanceof PlaceObjectTag p1) {
                mutated.add(MutedPlaceObject.from(p1));
                p1.setMatrix(offstageMatrix(p1.getMatrix()));
            }
            MATRIX matrix = place.getMatrix();
            overlays.add(new ScriptStoppedSpriteOverlay(
                    stoppedFrame, matrix == null ? null : new MATRIX(matrix), sprite.getTimeline().displayRect));
        }
        return overlays;
    }

    private static BufferedImage drawScriptStoppedSpriteOverlays(
            BufferedImage base, List<ScriptStoppedSpriteOverlay> overlays) {
        if (overlays == null || overlays.isEmpty()) {
            return base;
        }
        BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            for (ScriptStoppedSpriteOverlay overlay : overlays) {
                g.drawImage(overlay.image(), swfMatrixToAwt(overlay.matrix(), overlay.displayRect()), null);
            }
            g.drawImage(base, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static AffineTransform swfMatrixToAwt(MATRIX matrix, RECT displayRect) {
        double scaleX = matrix == null ? 1.0 : matrix.getScaleXFloat();
        double scaleY = matrix == null ? 1.0 : matrix.getScaleYFloat();
        double skew0 = matrix == null ? 0.0 : matrix.getRotateSkew0Float();
        double skew1 = matrix == null ? 0.0 : matrix.getRotateSkew1Float();
        double tx = matrix == null ? 0.0 : matrix.translateX;
        double ty = matrix == null ? 0.0 : matrix.translateY;
        double localX = displayRect == null ? 0.0 : displayRect.Xmin;
        double localY = displayRect == null ? 0.0 : displayRect.Ymin;
        return new AffineTransform(
                scaleX,
                skew1,
                skew0,
                scaleY,
                (tx + scaleX * localX + skew0 * localY) / 20.0,
                (ty + skew1 * localX + scaleY * localY) / 20.0);
    }

    private static void collectAndShiftEditTextBounds(SWF swf, ReadOnlyTagList tags, List<ShiftedPlaceObject> shifted) {
        for (Tag tag : tags) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || !(swf.getCharacter(childId) instanceof DefineEditTextTag editText)) {
                continue;
            }
            if (editText.bounds == null || (editText.bounds.Xmin == 0 && editText.bounds.Ymin == 0)) {
                continue;
            }
            // ffdec 26.0.0 can render DefineEditText from the PlaceObject origin and ignore a negative
            // local bounds origin. Fold the declared local offset into the temporary matrix before imaging.
            MATRIX original = place.getMatrix();
            MATRIX matrix = original == null ? new MATRIX() : new MATRIX(original);
            matrix.translateX += editText.bounds.Xmin;
            matrix.translateY += editText.bounds.Ymin;
            shifted.add(new ShiftedPlaceObject(place, original == null ? null : new MATRIX(original)));
            place.setMatrix(matrix);
        }
    }

    private static MATRIX offstageMatrix(MATRIX original) {
        MATRIX matrix = original == null ? new MATRIX() : new MATRIX(original);
        matrix.translateX = 10_000_000;
        matrix.translateY = 10_000_000;
        return matrix;
    }

    private static void resetAllTimelines(SWF swf) {
        swf.resetTimeline();
        for (CharacterTag ch : swf.getCharacters(false).values()) {
            if (ch instanceof DefineSpriteTag spr) {
                spr.resetTimeline();
            }
        }
    }

    /**
     * 枚举所有 DefineEditText 抽取可读文本（去重）。
     *
     * <p>DefineText 用 glyph index，抽不到原始 unicode（要查 font glyph 反向映射），跳过 ——
     * Worker 端 SwfTextExtractor 也是这么做的。
     */
    public List<String> extractAllTexts(ParseResult parsed) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Integer, CharacterTag> chars = parsed.swf().getCharacters(false);
        for (CharacterTag tag : chars.values()) {
            if (tag instanceof DefineEditTextTag editText) {
                for (String text : editText.getTexts()) {
                    addUnique(result, seen, text);
                }
                addUnique(result, seen, editText.initialText);
            }
        }
        return result;
    }

    /** 分析根时间轴里的视觉页面 / 密集 tips 布局。 */
    public RootPageAnalysis analyzeRootPages(ParseResult parsed, int frameIndex, Set<Integer> baseSuppressSet) {
        return analyzeRootPages(parsed, frameIndex, baseSuppressSet, List.of());
    }

    /** 收集主帧上已经可见的 DefineSprite 及其子 sprite,避免把主图已有文字再抽成独立说明。 */
    public Set<Integer> collectVisibleMainSpriteIds(ParseResult parsed, int frameIndex, Set<Integer> suppressIds) {
        Set<Integer> out = new HashSet<>();
        Set<Integer> suppressed = suppressIds == null ? Set.of() : suppressIds;
        for (PlacedSprite placed : collectRootPlacedSprites(parsed, frameIndex)) {
            if (suppressed.contains(placed.characterId())) {
                continue;
            }
            if (placed.character() instanceof DefineSpriteTag sprite) {
                collectReachableSpriteIds(parsed.swf(), sprite, suppressed, out, new HashSet<>());
            }
        }
        return out;
    }

    /** 抽取主帧上已可见 sprite 内的文本,用于避免主图文字再次输出成说明卡。 */
    public List<String> extractVisibleMainTexts(ParseResult parsed, int frameIndex, Set<Integer> suppressIds) {
        List<String> out = new ArrayList<>();
        Set<Integer> suppressed = suppressIds == null ? Set.of() : suppressIds;
        for (PlacedSprite placed : collectRootPlacedSprites(parsed, frameIndex)) {
            if (suppressed.contains(placed.characterId())) {
                continue;
            }
            if (placed.character() instanceof DefineSpriteTag sprite) {
                collectVisibleMainTexts(parsed.swf(), sprite, suppressed, out, new HashSet<>());
            }
        }
        return out;
    }

    /** 抽取主帧上可见的长文本说明,并返回这些文本框的 character id,由调用方在主图渲染时屏蔽。 */
    public MainFrameTextExtraction extractVisibleMainSelfRenderTexts(
            ParseResult parsed, int frameIndex, Set<Integer> suppressIds) {
        SWF swf = parsed.swf();
        Set<Integer> suppressed = suppressIds == null ? Set.of() : suppressIds;
        List<TextPiece> pieces = new ArrayList<>();
        Set<Integer> editTextIds = new HashSet<>();
        for (PlacedSprite placed : collectRootPlacedSprites(parsed, frameIndex)) {
            if (suppressed.contains(placed.characterId())) {
                continue;
            }
            CharacterTag child = placed.character();
            if (child instanceof DefineEditTextTag editText) {
                addMainSelfRenderTextPiece(placed.characterId(), 0, placed.x(), placed.y(), editText, pieces);
            } else if (child instanceof DefineSpriteTag sprite) {
                collectVisibleMainSelfRenderTextPieces(
                        swf, sprite, placed.x(), placed.y(), suppressed, pieces, new HashSet<>());
            }
        }
        pieces = sortTextPieces(pieces).stream()
                .filter(SwfDecoderFacade::shouldSelfRenderText)
                .toList();
        for (TextPiece piece : pieces) {
            editTextIds.add(piece.characterId());
        }
        if (pieces.isEmpty()) {
            return new MainFrameTextExtraction(Set.of(), List.of());
        }
        String text = joinSpriteText(pieces);
        List<TextBlock> blocks = toTextBlocks(pieces);
        String signature = signatureFor(text, null);
        DefineSpriteContent content = new DefineSpriteContent(0, text, blocks, null, signature, false, false);
        return new MainFrameTextExtraction(Set.copyOf(editTextIds), List.of(content));
    }

    private static void collectVisibleMainSelfRenderTextPieces(
            SWF swf,
            DefineSpriteTag sprite,
            int offsetX,
            int offsetY,
            Set<Integer> suppressed,
            List<TextPiece> out,
            Set<Integer> visitedSprites) {
        if (!visitedSprites.add(sprite.getCharacterId())) {
            return;
        }
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || suppressed.contains(childId)) {
                continue;
            }
            MATRIX matrix = place.getMatrix();
            int x = offsetX + (matrix == null ? 0 : matrix.translateX / 20);
            int y = offsetY + (matrix == null ? 0 : matrix.translateY / 20);
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineEditTextTag editText) {
                addMainSelfRenderTextPiece(childId, place.getDepth(), x, y, editText, out);
            } else if (child instanceof DefineSpriteTag nested) {
                collectVisibleMainSelfRenderTextPieces(swf, nested, x, y, suppressed, out, visitedSprites);
            }
        }
        visitedSprites.remove(sprite.getCharacterId());
    }

    private static void addMainSelfRenderTextPiece(
            int characterId, int depth, int x, int y, DefineEditTextTag editText, List<TextPiece> out) {
        String text = cleanEditText(editText);
        if (!text.isBlank()) {
            out.add(new TextPiece(characterId, depth, x, y, text, editText.leading));
        }
    }

    private static void collectVisibleMainTexts(
            SWF swf, DefineSpriteTag sprite, Set<Integer> suppressed, List<String> out, Set<Integer> visited) {
        int spriteId = sprite.getCharacterId();
        if (!visited.add(spriteId)) {
            return;
        }
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || suppressed.contains(childId)) {
                continue;
            }
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineEditTextTag editText) {
                String text = cleanEditText(editText);
                if (!text.isBlank()) {
                    out.add(text);
                }
            } else if (child instanceof DefineSpriteTag childSprite) {
                collectVisibleMainTexts(swf, childSprite, suppressed, out, visited);
            }
        }
        visited.remove(spriteId);
    }

    private static void collectReachableSpriteIds(
            SWF swf, DefineSpriteTag sprite, Set<Integer> suppressed, Set<Integer> out, Set<Integer> visited) {
        int spriteId = sprite.getCharacterId();
        if (!visited.add(spriteId)) {
            return;
        }
        out.add(spriteId);
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || suppressed.contains(childId)) {
                continue;
            }
            if (swf.getCharacter(childId) instanceof DefineSpriteTag childSprite) {
                collectReachableSpriteIds(swf, childSprite, suppressed, out, visited);
            }
        }
        visited.remove(spriteId);
    }

    /** 分析根时间轴里的视觉页面 / 密集 tips 布局。 */
    public RootPageAnalysis analyzeRootPages(
            ParseResult parsed, int frameIndex, Set<Integer> baseSuppressSet, List<Set<Integer>> visualStateGroups) {
        List<PlacedSprite> rootSprites = collectRootPlacedSprites(parsed, frameIndex);
        RootPageRenderContext context = new RootPageRenderContext(
                parsed,
                frameIndex,
                rootSprites,
                baseSuppressSet == null ? Set.of() : Set.copyOf(baseSuppressSet),
                visualStateGroups == null ? List.of() : visualStateGroups,
                new HashSet<>(),
                new HashSet<>(),
                new HashSet<>(),
                new HashSet<>(),
                new ArrayList<>());
        for (RootPageRenderStrategy strategy : ROOT_PAGE_RENDER_STRATEGIES) {
            strategy.collect(this, context);
        }
        return new RootPageAnalysis(
                context.absorbedSpriteIds(),
                context.unsuppressedSpriteIds(),
                context.activityDetailSpriteIds(),
                context.visualPages());
    }

    private static void addSpriteVisualPage(
            SWF swf,
            int spriteId,
            DefineSpriteTag sprite,
            Set<String> seenPageSignatures,
            List<VisualPageContent> visualPages) {
        PopupPageStats stats = inspectPopupPage(swf, sprite);
        SpriteTextContent textContent = extractSpriteTextContent(swf, sprite);
        Set<Integer> suppressIds = new HashSet<>(stats.tipSpriteIds());
        suppressIds.addAll(collectEditTextCharacterIds(swf, sprite, new HashSet<>()));
        BufferedImage pageImage = renderSpriteFrameSuppressing(swf, sprite, suppressIds);
        String signature = pageSignatureFor(textContent.text(), pageImage, Set.of(spriteId));
        if (!signature.isBlank() && seenPageSignatures.add(signature)) {
            visualPages.add(new VisualPageContent(
                    spriteId,
                    pageImage,
                    textContent.text(),
                    textContent.blocks(),
                    signature,
                    VisualPageKind.SPRITE_POPUP));
        }
    }

    private void addRootVisualStatePage(
            ParseResult parsed,
            int frameIndex,
            Set<Integer> baseSuppressSet,
            Set<Integer> visualStateGroup,
            Set<Integer> activityDetailSpriteIds,
            int sourceSpriteId,
            Set<String> seenPageSignatures,
            List<VisualPageContent> visualPages) {
        Set<Integer> suppressIds = new HashSet<>(baseSuppressSet == null ? Set.of() : baseSuppressSet);
        suppressIds.removeAll(visualStateGroup == null ? Set.of() : visualStateGroup);
        suppressIds.addAll(activityDetailSpriteIds == null ? Set.of() : activityDetailSpriteIds);
        BufferedImage pageImage = renderMainFrameSuppressing(parsed, frameIndex, suppressIds);
        String signature = pageSignatureFor("", pageImage, visualStateGroup == null ? Set.of() : visualStateGroup);
        if (signature.isBlank() || !seenPageSignatures.add(signature)) {
            return;
        }
        visualPages.add(new VisualPageContent(
                sourceSpriteId, pageImage, "", List.of(), signature, VisualPageKind.ROOT_VISUAL_STATE));
    }

    private static List<Set<Integer>> visibleStateGroupsFor(int spriteId, List<Set<Integer>> visualStateGroups) {
        if (visualStateGroups == null || visualStateGroups.isEmpty()) {
            return List.of(Set.of(spriteId));
        }
        List<Set<Integer>> out = new ArrayList<>();
        Set<Set<Integer>> seen = new HashSet<>();
        for (Set<Integer> group : visualStateGroups) {
            if (group != null && group.contains(spriteId)) {
                Set<Integer> copy = Set.copyOf(group);
                if (seen.add(copy)) {
                    out.add(copy);
                }
            }
        }
        return out.isEmpty() ? List.of(Set.of(spriteId)) : out;
    }

    /** 从指定 character id 集合中抽取可解释的 DefineSprite 内容。 */
    public List<DefineSpriteContent> extractDefineSpriteContents(ParseResult parsed, Set<Integer> spriteIds) {
        return extractDefineSpriteContents(parsed, spriteIds, Set.of());
    }

    /** 从指定 character id 集合中抽取可解释的 DefineSprite 内容，并排除已经归属到页面内的 sprite。 */
    public List<DefineSpriteContent> extractDefineSpriteContents(
            ParseResult parsed, Set<Integer> spriteIds, Set<Integer> excludedSpriteIds) {
        SWF swf = parsed.swf();
        List<DefineSpriteContent> result = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();
        Set<Integer> candidateIds = scanDefineSpriteCandidateIds(parsed, spriteIds);
        for (Integer spriteId : candidateIds.stream()
                .filter(id -> id != null && id > 0)
                .sorted()
                .toList()) {
            if (excludedSpriteIds != null && excludedSpriteIds.contains(spriteId)) {
                continue;
            }
            CharacterTag tag = swf.getCharacter(spriteId);
            if (!(tag instanceof DefineSpriteTag sprite)) {
                continue;
            }
            SpriteTextContent textContent = extractSpriteTextContent(swf, sprite);
            BufferedImage fullImage = renderTimelineFrame(sprite.getTimeline(), 0);
            BufferedImage textlessImage = renderSpriteFrameWithoutEditText(swf, sprite);
            SpriteStats stats = inspectSprite(swf, sprite, fullImage, parsed.stageWidth(), parsed.stageHeight());
            if (!isMeaningfulDefineSprite(spriteId, textContent.text(), stats, spriteIds)) {
                continue;
            }
            boolean preferImage = stats.visualPage();
            Set<Integer> editTextIds = collectEditTextCharacterIds(swf, sprite, new HashSet<>());
            BufferedImage image = preferImage ? renderSpriteFrameSuppressing(swf, sprite, editTextIds) : textlessImage;
            String signature = signatureFor(textContent.text(), image);
            if (signature.isBlank() || !seenSignatures.add(signature)) {
                continue;
            }
            result.add(new DefineSpriteContent(
                    spriteId, textContent.text(), textContent.blocks(), image, signature, preferImage, false));
        }
        return result;
    }

    /** 抽取由 hover / 问号等触发的说明型 sprite。图片型说明保留原图,文本型说明进入底部文本区。 */
    public List<DefineSpriteContent> extractActivityDetailSpriteContents(
            ParseResult parsed, Set<Integer> spriteIds, Set<Integer> excludedSpriteIds) {
        if (spriteIds == null || spriteIds.isEmpty()) {
            return List.of();
        }
        SWF swf = parsed.swf();
        List<DefineSpriteContent> result = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();
        for (Integer spriteId :
                spriteIds.stream().filter(id -> id != null && id > 0).sorted().toList()) {
            if (excludedSpriteIds != null && excludedSpriteIds.contains(spriteId)) {
                continue;
            }
            CharacterTag tag = swf.getCharacter(spriteId);
            if (!(tag instanceof DefineSpriteTag sprite)) {
                continue;
            }
            SpriteTextContent textContent = extractSpriteTextContent(swf, sprite);
            BufferedImage image = renderTimelineFrame(sprite.getTimeline(), 0);
            SpriteStats stats = inspectSprite(swf, sprite, image, parsed.stageWidth(), parsed.stageHeight());
            String cleanedText = TooltipComposer.cleanText(textContent.text()).replaceAll("\\s+", "");
            if (stats.stageRatio() < 0.01 && cleanedText.length() < SELF_RENDER_TEXT_MIN_LENGTH) {
                continue;
            }
            boolean preferImage = stats.stageRatio() >= 0.01;
            String signature = signatureFor(textContent.text(), image);
            if (signature.isBlank() || !seenSignatures.add(signature)) {
                continue;
            }
            result.add(new DefineSpriteContent(
                    spriteId,
                    textContent.text(),
                    textContent.blocks(),
                    preferImage ? image : null,
                    signature,
                    preferImage,
                    true));
        }
        return result;
    }

    private static Set<Integer> scanDefineSpriteCandidateIds(ParseResult parsed, Set<Integer> seedIds) {
        Set<Integer> ids = new HashSet<>();
        if (seedIds != null) {
            for (Integer seedId : seedIds) {
                if (seedId != null && seedId > 0) {
                    ids.add(seedId);
                }
            }
        }
        SWF swf = parsed.swf();
        for (CharacterTag tag : swf.getCharacters(false).values()) {
            if (!(tag instanceof DefineSpriteTag sprite)) {
                continue;
            }
            SpriteTextContent textContent = extractSpriteTextContent(swf, sprite);
            BufferedImage image = renderTimelineFrame(sprite.getTimeline(), 0);
            SpriteStats stats = inspectSprite(swf, sprite, image, parsed.stageWidth(), parsed.stageHeight());
            if (isMeaningfulDefineSprite(sprite.getCharacterId(), textContent.text(), stats, seedIds)) {
                ids.add(sprite.getCharacterId());
            }
        }
        return ids;
    }

    private static List<PlacedSprite> collectRootPlacedSprites(ParseResult parsed, int frameIndex) {
        List<PlacedSprite> out = new ArrayList<>();
        int currentFrame = 0;
        for (Tag tag : parsed.tags()) {
            if (tag instanceof PlaceObjectTypeTag place && currentFrame == frameIndex) {
                int childId = extractCharacterId(place);
                if (childId > 0) {
                    MATRIX matrix = place.getMatrix();
                    int x = matrix == null ? 0 : matrix.translateX / 20;
                    int y = matrix == null ? 0 : matrix.translateY / 20;
                    out.add(new PlacedSprite(
                            childId, place.getInstanceName(), x, y, parsed.swf().getCharacter(childId)));
                }
            } else if (tag instanceof com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.ShowFrameTag) {
                if (currentFrame >= frameIndex) {
                    break;
                }
                currentFrame++;
            }
        }
        return out;
    }

    private static DenseTipLayout detectDenseTopLevelTips(List<PlacedSprite> rootSprites) {
        List<PlacedSprite> tipSprites = new ArrayList<>();
        int itemLikeCount = 0;
        for (PlacedSprite placed : rootSprites) {
            String name = placed.instanceName() == null ? "" : placed.instanceName();
            if (isTipInstance(name) && placed.character() instanceof DefineSpriteTag) {
                tipSprites.add(placed);
            } else if (isItemInstance(name) && placed.character() instanceof DefineSpriteTag) {
                itemLikeCount++;
            }
        }
        if (tipSprites.size() >= 4 && itemLikeCount >= Math.max(3, tipSprites.size() / 2)) {
            return new DenseTipLayout(collectDenseItemTipIds(tipSprites));
        }
        return new DenseTipLayout(Set.of());
    }

    private static VisualStatePageCandidate findVisualStatePageCandidate(
            SWF swf, List<PlacedSprite> rootSprites, Set<Integer> visualStateGroup, int stageWidth, int stageHeight) {
        VisualStatePageCandidate best = null;
        for (PlacedSprite placed : rootSprites) {
            if (!visualStateGroup.contains(placed.characterId())
                    || !(placed.character() instanceof DefineSpriteTag sprite)) {
                continue;
            }
            BufferedImage image = renderTimelineFrame(sprite.getTimeline(), 0);
            double ratio = visibleBoundsRatio(image, stageWidth, stageHeight);
            String name = placed.instanceName() == null ? "" : placed.instanceName();
            boolean popupName = isPopupPageInstance(name);
            double minRatio = popupName ? 0.12 : 0.18;
            if (ratio < minRatio) {
                continue;
            }
            if (!popupName && visualStateGroup.size() < 2) {
                continue;
            }
            if (best == null || ratio > best.stageRatio()) {
                best = new VisualStatePageCandidate(placed.characterId(), ratio);
            }
        }
        return best;
    }

    private static Set<Integer> collectDenseItemTipIds(List<PlacedSprite> tipSprites) {
        Set<Integer> ids = new HashSet<>();
        int minNonFirstTipY = tipSprites.stream()
                .filter(sprite -> !isFirstTipInstance(sprite.instanceName()))
                .mapToInt(PlacedSprite::y)
                .min()
                .orElse(Integer.MAX_VALUE);
        for (PlacedSprite tip : tipSprites) {
            if (isFirstTipInstance(tip.instanceName()) && tip.y() + 20 < minNonFirstTipY) {
                continue;
            }
            ids.add(tip.characterId());
        }
        return ids;
    }

    private static PopupPageStats inspectPopupPage(SWF swf, DefineSpriteTag sprite) {
        Set<Integer> tipSpriteIds = new HashSet<>();
        int itemLikeCount = 0;
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0) {
                continue;
            }
            CharacterTag child = swf.getCharacter(childId);
            String name = place.getInstanceName() == null ? "" : place.getInstanceName();
            if (isTipInstance(name) && child instanceof DefineSpriteTag) {
                tipSpriteIds.add(childId);
            } else if (isItemInstance(name) && child instanceof DefineSpriteTag) {
                itemLikeCount++;
            }
        }
        return new PopupPageStats(tipSpriteIds, tipSpriteIds.size() >= 3 && itemLikeCount >= 3);
    }

    private static Set<Integer> collectEmbeddedActivityDetailSpriteIds(SWF swf, DefineSpriteTag sprite) {
        Set<Integer> ids = new HashSet<>();
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0 || !(swf.getCharacter(childId) instanceof DefineSpriteTag)) {
                continue;
            }
            String name = place.getInstanceName() == null ? "" : place.getInstanceName();
            if (isTipInstance(name)) {
                ids.add(childId);
            }
        }
        return ids;
    }

    private static SpriteTextContent extractSpriteTextContent(SWF swf, DefineSpriteTag sprite) {
        List<TextPiece> pieces = new ArrayList<>();
        collectSpriteTextPieces(swf, sprite, 0, 0, pieces, new HashSet<>());
        pieces = sortTextPieces(pieces).stream()
                .filter(SwfDecoderFacade::shouldSelfRenderText)
                .toList();
        return new SpriteTextContent(joinSpriteText(pieces), toTextBlocks(pieces));
    }

    private static List<TextPiece> sortTextPieces(List<TextPiece> pieces) {
        return pieces.stream()
                .sorted(Comparator.comparingInt(TextPiece::y)
                        .thenComparingInt(TextPiece::x)
                        .thenComparingInt(TextPiece::depth))
                .toList();
    }

    private static boolean isTipInstance(String name) {
        return name != null && name.matches("(?i)tips?\\d*");
    }

    private static boolean isFirstTipInstance(String name) {
        return name != null && name.matches("(?i)tips?1");
    }

    private static boolean isItemInstance(String name) {
        return name != null && name.matches("(?i)(mc|weaponMc|itemMc|rewardMc)\\d+");
    }

    private static boolean isPopupPageInstance(String name) {
        return name != null && name.matches("(?i)(tc|tips?|tip|dialog|dlg|panel|page|pop|popup|window)\\d*");
    }

    private static boolean isMeaningfulDefineSprite(
            int spriteId, String text, SpriteStats stats, Set<Integer> seedIds) {
        String cleanedText = TooltipComposer.cleanText(text).replaceAll("\\s+", "");
        if (seedIds != null && seedIds.contains(spriteId)) {
            return stats.visualPage() || cleanedText.length() >= SELF_RENDER_TEXT_MIN_LENGTH;
        }
        if (cleanedText.length() >= SELF_RENDER_TEXT_MIN_LENGTH
                && containsCjk(cleanedText)
                && stats.directTextCount() > 0) {
            return true;
        }
        if (stats.visualPage()) {
            return true;
        }
        return cleanedText.length() >= 24 && containsCjk(cleanedText) && stats.nestedSpriteCount() > 0;
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isWideChar(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void collectSpriteTextPieces(
            SWF swf,
            DefineSpriteTag sprite,
            int offsetX,
            int offsetY,
            List<TextPiece> out,
            Set<Integer> visitedSprites) {
        if (!visitedSprites.add(sprite.getCharacterId())) {
            return;
        }
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0) {
                continue;
            }
            MATRIX matrix = place.getMatrix();
            int x = offsetX + (matrix == null ? 0 : matrix.translateX / 20);
            int y = offsetY + (matrix == null ? 0 : matrix.translateY / 20);
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineEditTextTag editText) {
                String text = cleanEditText(editText);
                if (!text.isBlank()) {
                    out.add(new TextPiece(childId, place.getDepth(), x, y, text, editText.leading));
                }
            } else if (child instanceof DefineSpriteTag nested) {
                collectSpriteTextPieces(swf, nested, x, y, out, visitedSprites);
            }
        }
        visitedSprites.remove(sprite.getCharacterId());
    }

    private static int extractCharacterId(PlaceObjectTypeTag place) {
        if (place instanceof PlaceObject2Tag p2) {
            return p2.placeFlagHasCharacter ? p2.characterId : -1;
        }
        if (place instanceof PlaceObjectTag p1) {
            return p1.getCharacterId();
        }
        return -1;
    }

    private static SpriteStats inspectSprite(
            SWF swf, DefineSpriteTag sprite, BufferedImage image, int stageWidth, int stageHeight) {
        int directTextCount = 0;
        int nestedSpriteCount = 0;
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0) {
                continue;
            }
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineEditTextTag) {
                directTextCount++;
            } else if (child instanceof DefineSpriteTag) {
                nestedSpriteCount++;
            }
        }
        double stageRatio = visibleBoundsRatio(image, stageWidth, stageHeight);
        boolean visualPage = nestedSpriteCount >= 3 && stageRatio >= 0.20;
        return new SpriteStats(directTextCount, nestedSpriteCount, stageRatio, visualPage);
    }

    private static double visibleBoundsRatio(BufferedImage image, int stageWidth, int stageHeight) {
        if (image == null || image.getWidth() < 4 || image.getHeight() < 4) {
            return 0.0;
        }
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                int rgb = argb & 0x00ffffff;
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                if (alpha > 16 && r + g + b > 30) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return 0.0;
        }
        int visibleBoundsArea = (maxX - minX + 1) * (maxY - minY + 1);
        int stageArea = Math.max(1, stageWidth * stageHeight);
        return visibleBoundsArea / (double) stageArea;
    }

    private static String cleanEditText(DefineEditTextTag editText) {
        String raw = editText.initialText;
        if (raw == null || raw.isBlank()) {
            raw = String.join("\n", editText.getTexts());
        }
        return TooltipComposer.cleanText(preserveParagraphBreaks(raw));
    }

    private static String preserveParagraphBreaks(String raw) {
        if (raw == null || raw.indexOf('<') < 0) {
            return raw == null ? "" : raw;
        }
        return raw.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>\\s*<p\\b", "\n<p")
                .replaceAll("(?i)</p>", "\n");
    }

    private static String joinSpriteText(List<TextPiece> pieces) {
        if (pieces.isEmpty()) {
            return "";
        }
        if (shouldJoinAsColumns(pieces)) {
            return joinSpriteTextColumns(pieces);
        }
        List<String> lines = new ArrayList<>();
        for (TextPiece piece : pieces) {
            String text = normalizePieceText(piece.text());
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return String.join("\n\n", lines);
    }

    private static List<TextBlock> toTextBlocks(List<TextPiece> pieces) {
        return pieces.stream()
                .map(piece -> new TextBlock(
                        piece.depth(), piece.x(), piece.y(), normalizePieceText(piece.text()), piece.leading()))
                .filter(block -> !block.text().isBlank())
                .toList();
    }

    private static boolean shouldSelfRenderText(TextPiece piece) {
        return textLengthWithoutWhitespace(piece.text()) >= SELF_RENDER_TEXT_MIN_LENGTH;
    }

    private static int textLengthWithoutWhitespace(String text) {
        return TooltipComposer.cleanText(text).replaceAll("\\s+", "").length();
    }

    private static boolean isActivityDetailText(String text) {
        String cleaned = TooltipComposer.cleanText(text);
        return cleaned.contains("活动细则")
                || cleaned.contains("活动规则")
                || cleaned.contains("注意事项")
                || cleaned.contains("规则说明")
                || cleaned.contains("温馨提示");
    }

    private static boolean shouldJoinAsColumns(List<TextPiece> pieces) {
        if (pieces.size() < 2) {
            return false;
        }
        int minX = pieces.stream().mapToInt(TextPiece::x).min().orElse(0);
        int maxX = pieces.stream().mapToInt(TextPiece::x).max().orElse(0);
        int minY = pieces.stream().mapToInt(TextPiece::y).min().orElse(0);
        int maxY = pieces.stream().mapToInt(TextPiece::y).max().orElse(0);
        return maxX - minX >= 80 && maxY - minY <= 70;
    }

    private static String joinSpriteTextColumns(List<TextPiece> pieces) {
        List<TextColumn> columns = pieces.stream()
                .sorted(Comparator.comparingInt(TextPiece::x).thenComparingInt(TextPiece::y))
                .map(TextColumn::from)
                .filter(column -> !column.lines().isEmpty())
                .toList();
        if (columns.size() < 2) {
            return joinSpriteTextSequential(pieces);
        }

        int baseY = columns.stream().mapToInt(TextColumn::y).min().orElse(0);
        int maxRows = 0;
        for (TextColumn column : columns) {
            int offset = column.rowOffset(baseY);
            maxRows = Math.max(maxRows, offset + column.lines().size());
        }

        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size() - 1; i++) {
            int maxLen = 0;
            for (String line : columns.get(i).lines()) {
                maxLen = Math.max(maxLen, visualLength(line));
            }
            widths[i] = maxLen + 6;
        }

        List<String> rows = new ArrayList<>();
        for (int row = 0; row < maxRows; row++) {
            StringBuilder sb = new StringBuilder();
            boolean hasText = false;
            for (int col = 0; col < columns.size(); col++) {
                TextColumn column = columns.get(col);
                String cell = column.lineAt(row, baseY);
                if (!cell.isBlank()) {
                    hasText = true;
                }
                if (col + 1 < columns.size()) {
                    sb.append(padRight(cell, widths[col]));
                } else {
                    sb.append(cell);
                }
            }
            if (hasText) {
                rows.add(sb.toString().stripTrailing());
            }
        }
        return String.join("\n", rows);
    }

    private static String joinSpriteTextSequential(List<TextPiece> pieces) {
        List<String> parts = new ArrayList<>();
        for (TextPiece piece : pieces) {
            String text = normalizePieceText(piece.text());
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join("\n\n", parts);
    }

    private static String normalizePieceText(String text) {
        List<String> lines = splitTextLines(text);
        return String.join("\n", lines);
    }

    private static List<String> splitTextLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] rawLines = text.replace("\r", "").split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            lines.add(rawLine.strip());
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String padRight(String value, int width) {
        StringBuilder out = new StringBuilder(value == null ? "" : value);
        while (visualLength(out.toString()) < width) {
            out.append(' ');
        }
        return out.toString();
    }

    private static int visualLength(String value) {
        int len = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            len += isWideChar(c) ? 2 : 1;
        }
        return len;
    }

    private static boolean isWideChar(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static String signatureFor(String text, BufferedImage image) {
        String normalizedText = TooltipComposer.cleanText(text).replaceAll("\\s+", "");
        if (!normalizedText.isBlank()) {
            return "T:" + normalizedText;
        }
        if (image == null) {
            return "";
        }
        CRC32 crc = new CRC32();
        int step = Math.max(1, (int) Math.sqrt((image.getWidth() * image.getHeight()) / 4096.0));
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xff) > 16) {
                    crc.update((argb >>> 24) & 0xff);
                    crc.update((argb >>> 16) & 0xff);
                    crc.update((argb >>> 8) & 0xff);
                    crc.update(argb & 0xff);
                }
            }
        }
        return "I:" + image.getWidth() + "x" + image.getHeight() + ":" + Long.toHexString(crc.getValue());
    }

    private static String pageSignatureFor(String text, BufferedImage image, Set<Integer> visibleSpriteIds) {
        String visibleKey = visibleSpriteIds.stream()
                .sorted()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "P:" + visibleKey + ":" + signatureFor(text, image);
    }

    /**
     * 反编译所有 AS3 ScriptPack 到源码字符串。返回 className → 源码。
     *
     * <p>实现策略：用 {@code swf.exportActionScript(...)} 高层 API（ffdec GUI 的 export AS 走这条），
     * 自动遍历所有 ScriptPack、自动写到 dir 下按 className 镜像的目录树。8 参 settings 显式带
     * {@code assetsDir} 与 {@code includeAllClasses}—— 5 参版本 assetsDir=null 在 26.0.0 会让
     * export 静默 skip 写盘。低层 {@code pack.export} 与 {@code pack.toSource} 在 26.0.0 对
     * traits 列表的内部假设变化（null 抛 NPE，empty list 抛越界），高层 API 是规避这两个雷的稳定路径。
     *
     * <p>反编译完后扫描 dir 下所有 .as 文件，从相对路径反推 className（{@code com/foo/Bar.as → com.foo.Bar}）。
     */
    public Map<String, String> decompileAs3(ParseResult parsed) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        SWF swf = parsed.swf();
        Path tmpDir = Files.createTempDirectory("strikegod-as3-");
        try {
            ScriptExportSettings settings = new ScriptExportSettings(
                    ScriptExportMode.AS,
                    false, // singleFile
                    false, // exportEmbed
                    false, // exportEmbedFlaMode
                    false, // ignoreFrameScripts
                    tmpDir.toString(), // assetsDir：不能为 null
                    true, // includeAllClasses
                    false); // ignoreAccessibility
            swf.exportActionScript(new SilentAbortHandler(), tmpDir.toString(), settings, false, null);

            try (Stream<Path> walk = Files.walk(tmpDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".as"))
                        .forEach(asFile -> {
                            String className = relativeAsPathToClassName(tmpDir, asFile);
                            try {
                                result.put(className, Files.readString(asFile));
                            } catch (IOException ex) {
                                log.warn("读取反编译 .as 失败: {} — {}", className, ex.getMessage());
                            }
                        });
            }
        } finally {
            cleanupDirectory(tmpDir);
        }
        return result;
    }

    /**
     * 把指定 character id 的 sprite 第 0 帧渲染为 PNG，写入 {@code out}。
     *
     * @throws IllegalArgumentException 该 character id 不是 DefineSprite
     */
    public void exportSpriteToPng(ParseResult parsed, int characterId, OutputStream out) throws IOException {
        CharacterTag tag = parsed.swf().getCharacter(characterId);
        if (!(tag instanceof DefineSpriteTag sprite)) {
            throw new IllegalArgumentException("character " + characterId + " 不是 DefineSprite (实际: "
                    + (tag == null ? "null" : tag.getClass().getSimpleName()) + ")");
        }
        BufferedImage bi = renderTimelineFrame(sprite.getTimeline(), 0);
        if (!ImageIO.write(bi, "PNG", out)) {
            throw new IOException("PNG ImageWriter 不可用");
        }
    }

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
                0 // selectedDepth (no selection)
                );
        return img.getBufferedImage();
    }

    private static BufferedImage renderSpriteFrameWithoutEditText(SWF swf, DefineSpriteTag sprite) {
        Set<Integer> editTextIds = collectEditTextCharacterIds(swf, sprite, new HashSet<>());
        return renderSpriteFrameSuppressing(swf, sprite, editTextIds);
    }

    private static BufferedImage renderSpriteFrameSuppressing(
            SWF swf, DefineSpriteTag sprite, Set<Integer> suppressCharacterIds) {
        return renderSpriteFrameSuppressing(swf, sprite, suppressCharacterIds, 0);
    }

    private static BufferedImage renderSpriteFrameSuppressing(
            SWF swf, DefineSpriteTag sprite, Set<Integer> suppressCharacterIds, int frameIndex) {
        if (suppressCharacterIds == null || suppressCharacterIds.isEmpty()) {
            return renderTimelineFrame(sprite.getTimeline(), frameIndex);
        }
        List<MutedPlaceObject> mutated = new ArrayList<>();
        try {
            for (CharacterTag ch : swf.getCharacters(false).values()) {
                if (ch instanceof DefineSpriteTag spr) {
                    collectAndSuppress(spr.getTags(), suppressCharacterIds, mutated);
                }
            }
            resetAllTimelines(swf);
            return renderTimelineFrame(sprite.getTimeline(), frameIndex);
        } finally {
            for (MutedPlaceObject p : mutated) {
                p.restore();
            }
            resetAllTimelines(swf);
        }
    }

    private static boolean containsSuppressedDescendant(
            SWF swf, DefineSpriteTag sprite, Set<Integer> suppressCharacterIds, Set<Integer> visitedSprites) {
        if (suppressCharacterIds == null || suppressCharacterIds.isEmpty()) {
            return false;
        }
        if (!visitedSprites.add(sprite.getCharacterId())) {
            return false;
        }
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0) {
                continue;
            }
            if (suppressCharacterIds.contains(childId)) {
                visitedSprites.remove(sprite.getCharacterId());
                return true;
            }
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineSpriteTag nested
                    && containsSuppressedDescendant(swf, nested, suppressCharacterIds, visitedSprites)) {
                visitedSprites.remove(sprite.getCharacterId());
                return true;
            }
        }
        visitedSprites.remove(sprite.getCharacterId());
        return false;
    }

    private static Set<Integer> collectEditTextCharacterIds(
            SWF swf, DefineSpriteTag sprite, Set<Integer> visitedSprites) {
        Set<Integer> ids = new HashSet<>();
        if (!visitedSprites.add(sprite.getCharacterId())) {
            return ids;
        }
        for (Tag tag : sprite.getTags()) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            int childId = extractCharacterId(place);
            if (childId <= 0) {
                continue;
            }
            CharacterTag child = swf.getCharacter(childId);
            if (child instanceof DefineEditTextTag) {
                ids.add(childId);
            } else if (child instanceof DefineSpriteTag nested) {
                ids.addAll(collectEditTextCharacterIds(swf, nested, visitedSprites));
            }
        }
        visitedSprites.remove(sprite.getCharacterId());
        return ids;
    }

    private static String relativeAsPathToClassName(Path root, Path asFile) {
        Path rel = root.relativize(asFile);
        String s = rel.toString().replace('\\', '/');
        if (s.endsWith(".as")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.replace('/', '.');
    }

    private static void cleanupDirectory(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignore) {
            // best-effort：临时目录残留不影响 production
        }
    }

    private static void addUnique(List<String> result, Set<String> seen, String raw) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (!trimmed.isEmpty() && seen.add(trimmed)) {
            result.add(trimmed);
        }
    }

    /** 任何抛出都 IGNORE，让 export 流程不会因单个 pack 异常中断；不重试避免无限循环。 */
    private static final class SilentAbortHandler implements AbortRetryIgnoreHandler {
        @Override
        public int handle(Throwable t) {
            log.debug("AS3 export 内部异常，忽略: {}", t.getMessage());
            return IGNORE_ALL;
        }

        @Override
        public AbortRetryIgnoreHandler getNewInstance() {
            return this;
        }
    }

    /** SWF 解析后可观测的核心数据。 */
    public record ParseResult(SWF swf, ReadOnlyTagList tags) {
        public int stageWidth() {
            return swf.displayRect.getWidth() / 20;
        }

        public int stageHeight() {
            return swf.displayRect.getHeight() / 20;
        }
    }

    private record SpriteStats(int directTextCount, int nestedSpriteCount, double stageRatio, boolean visualPage) {}

    private record PlacedSprite(int characterId, String instanceName, int x, int y, CharacterTag character) {}

    private record DenseTipLayout(Set<Integer> tipSpriteIds) {}

    private record VisualStatePageCandidate(int spriteId, double stageRatio) {}

    private interface RootPageRenderStrategy {
        void collect(SwfDecoderFacade facade, RootPageRenderContext context);
    }

    private record RootPageRenderContext(
            ParseResult parsed,
            int frameIndex,
            List<PlacedSprite> rootSprites,
            Set<Integer> baseSuppressSet,
            List<Set<Integer>> visualStateGroups,
            Set<String> seenPageSignatures,
            Set<Integer> absorbedSpriteIds,
            Set<Integer> unsuppressedSpriteIds,
            Set<Integer> activityDetailSpriteIds,
            List<VisualPageContent> visualPages) {}

    private static final class RootTimelineVisualStateStrategy implements RootPageRenderStrategy {
        @Override
        public void collect(SwfDecoderFacade facade, RootPageRenderContext context) {
            for (Set<Integer> visualStateGroup : context.visualStateGroups()) {
                if (visualStateGroup == null || visualStateGroup.isEmpty()) {
                    continue;
                }
                VisualStatePageCandidate candidate = findVisualStatePageCandidate(
                        context.parsed().swf(),
                        context.rootSprites(),
                        visualStateGroup,
                        context.parsed().stageWidth(),
                        context.parsed().stageHeight());
                if (candidate == null) {
                    continue;
                }
                CharacterTag candidateTag = context.parsed().swf().getCharacter(candidate.spriteId());
                if (!(candidateTag instanceof DefineSpriteTag candidateSprite)) {
                    continue;
                }
                Set<Integer> activityDetailSpriteIds =
                        collectEmbeddedActivityDetailSpriteIds(context.parsed().swf(), candidateSprite);
                facade.addRootVisualStatePage(
                        context.parsed(),
                        context.frameIndex(),
                        context.baseSuppressSet(),
                        visualStateGroup,
                        activityDetailSpriteIds,
                        candidate.spriteId(),
                        context.seenPageSignatures(),
                        context.visualPages());
                context.absorbedSpriteIds().addAll(visualStateGroup);
                context.activityDetailSpriteIds().addAll(activityDetailSpriteIds);
            }
        }
    }

    private static final class SelfContainedPopupSpriteStrategy implements RootPageRenderStrategy {
        @Override
        public void collect(SwfDecoderFacade facade, RootPageRenderContext context) {
            for (PlacedSprite placed : context.rootSprites()) {
                if (!(placed.character() instanceof DefineSpriteTag sprite)) {
                    continue;
                }
                PopupPageStats stats = inspectPopupPage(context.parsed().swf(), sprite);
                if (context.absorbedSpriteIds().contains(placed.characterId())) {
                    continue;
                }
                if (!stats.popupPage()) {
                    continue;
                }

                for (Set<Integer> unsuppressedForPage :
                        visibleStateGroupsFor(placed.characterId(), context.visualStateGroups())) {
                    addSpriteVisualPage(
                            context.parsed().swf(),
                            placed.characterId(),
                            sprite,
                            context.seenPageSignatures(),
                            context.visualPages());
                    context.absorbedSpriteIds().addAll(unsuppressedForPage);
                }
                context.absorbedSpriteIds().addAll(stats.tipSpriteIds());
                context.activityDetailSpriteIds().addAll(stats.tipSpriteIds());
            }
        }
    }

    private record FunctionStart(String name, int offset) {}

    private record ScriptStoppedSpriteOverlay(BufferedImage image, MATRIX matrix, RECT displayRect) {}

    private record PopupPageStats(Set<Integer> tipSpriteIds, boolean popupPage) {}

    private record SpriteTextContent(String text, List<TextBlock> blocks) {}

    private record TextPiece(int characterId, int depth, int x, int y, String text, int leading) {}

    private record MutedPlaceObject(
            Tag tag, boolean originalHasCharacter, int originalCharacterId, MATRIX originalMatrix) {
        static MutedPlaceObject from(PlaceObject2Tag tag) {
            return new MutedPlaceObject(tag, tag.placeFlagHasCharacter, tag.characterId, null);
        }

        static MutedPlaceObject from(PlaceObjectTag tag) {
            MATRIX matrix = tag.getMatrix() == null ? null : new MATRIX(tag.getMatrix());
            return new MutedPlaceObject(tag, true, tag.getCharacterId(), matrix);
        }

        void restore() {
            if (tag instanceof PlaceObject2Tag p2) {
                p2.placeFlagHasCharacter = originalHasCharacter;
                p2.characterId = originalCharacterId;
            } else if (tag instanceof PlaceObjectTag p1) {
                p1.setCharacterId(originalCharacterId);
                p1.setMatrix(originalMatrix);
            }
        }
    }

    private record ShiftedPlaceObject(PlaceObjectTypeTag tag, MATRIX originalMatrix) {
        void restore() {
            tag.setMatrix(originalMatrix);
        }
    }

    private record TextColumn(int x, int y, List<String> lines) {
        private static final int ESTIMATED_LINE_HEIGHT = 22;

        static TextColumn from(TextPiece piece) {
            return new TextColumn(piece.x(), piece.y(), splitTextLines(piece.text()));
        }

        int rowOffset(int baseY) {
            return Math.max(0, (int) Math.round((y - baseY) / (double) ESTIMATED_LINE_HEIGHT));
        }

        String lineAt(int row, int baseY) {
            int localRow = row - rowOffset(baseY);
            if (localRow < 0 || localRow >= lines.size()) {
                return "";
            }
            return lines.get(localRow);
        }
    }

    /** 一个被 AS 触发或隐藏的 DefineSprite 内容块。 */
    public record DefineSpriteContent(
            int spriteId,
            String text,
            List<TextBlock> textBlocks,
            BufferedImage image,
            String signature,
            boolean preferImage,
            boolean activityDetail) {
        public DefineSpriteContent(int spriteId, String text, BufferedImage image, String signature) {
            this(
                    spriteId,
                    text,
                    text.isBlank() ? List.of() : List.of(new TextBlock(0, 0, 0, text)),
                    image,
                    signature,
                    false,
                    false);
        }
    }

    /** 主帧上被抽离自绘的可见长文本,以及需要从主图渲染中屏蔽的文本框 character id。 */
    public record MainFrameTextExtraction(Set<Integer> suppressCharacterIds, List<DefineSpriteContent> contents) {}

    /** DefineSprite 中单个 DefineEditText 对应的一块文字。 */
    public record TextBlock(int depth, int x, int y, String text, int leading) {
        public TextBlock(int depth, int x, int y, String text) {
            this(depth, x, y, text, 0);
        }

        public boolean activityDetail() {
            return isActivityDetailText(text);
        }
    }

    /** 根时间轴上识别出的页面级内容与被页面吸收的 tips。 */
    public record RootPageAnalysis(
            Set<Integer> absorbedSpriteIds,
            Set<Integer> unsuppressedSpriteIds,
            Set<Integer> activityDetailSpriteIds,
            List<VisualPageContent> visualPages) {}

    /** 一个应该作为页面图展示的根时间轴视觉状态。 */
    public record VisualPageContent(
            int sourceSpriteId,
            BufferedImage image,
            String text,
            List<TextBlock> textBlocks,
            String signature,
            VisualPageKind kind) {
        public VisualPageContent(
                int sourceSpriteId, BufferedImage image, String text, List<TextBlock> textBlocks, String signature) {
            this(sourceSpriteId, image, text, textBlocks, signature, VisualPageKind.SPRITE_POPUP);
        }
    }

    public enum VisualPageKind {
        ROOT_VISUAL_STATE,
        SPRITE_POPUP
    }
}
