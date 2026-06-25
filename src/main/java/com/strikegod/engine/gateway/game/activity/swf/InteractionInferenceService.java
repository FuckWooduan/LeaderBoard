package com.strikegod.engine.gateway.game.activity.swf;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.ReadOnlyTagList;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.PlaceObject2Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.PlaceObjectTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.BoundedTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.PlaceObjectTypeTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.MATRIX;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.RECT;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通过静态分析 SWF 反编译出的 AS 脚本,推断 hover / click 触发器与目标内容的绑定关系。
 *
 * <p>本服务替代 Worker 端用 PowerShell + Win32 PostMessage 模拟悬浮/点击的运行时方案。
 * 采用静态推断的好处:
 *
 * <ul>
 *   <li>不依赖 Flash Player 真实运行时(Worker 后续会瘦身到只剩主帧截图)
 *   <li>可直接拿到"哪个 sprite ↔ 哪段说明文本"的语义关联,合成的 tooltip 比真截图信息密度高
 *   <li>速度提升数倍(原 N 次 player 启动 + 截图,现一次反编译 + 多次合成)
 * </ul>
 *
 * <p>本阶段实现:
 *
 * <ul>
 *   <li>调 SwfDecoderFacade.decompileAs3 拿所有 ScriptPack 源码
 *   <li>regex 扫 AS3 {@code addEventListener(MouseEvent.<EVT>, fn)} 绑定,记下
 *       trigger instance 名 + 事件类型 + handler 方法名
 *   <li>遍历主舞台 + 所有 DefineSprite 子 timeline 的 PlaceObject,建立
 *       instanceName → (characterId, displayBounds) 索引
 *   <li>handler body 里识别 {@code visible=true} / {@code addChild} / {@code gotoAndStop} 推断子页 target;
 *       识别 {@code xxx.text = "字面值"} 与 {@code xxx.text = ClassName.CONST} 推断 hover/click 文本来源
 *   <li>预扫所有 ScriptPack 抽 {@code public static const NAME:Type = "字面值"} 建常量池,供 const ref 解析
 * </ul>
 *
 * <p>当前 <strong>不做</strong>的事:
 *
 * <ul>
 *   <li>多重间接引用 (如 {@code obj.text = arr[i]} / {@code obj.text = func()}) ── 不展开
 *   <li>AS2 的 on(rollOver) / on(release) 绑定 ── 现版游戏 SWF 都是 AS3
 *   <li>嵌套 PlaceObject matrix 累乘到主舞台坐标 ── 一级嵌套(主舞台直接放置)正确
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionInferenceService {

    /**
     * AS3 mouse listener 模式:{@code <obj>.addEventListener(MouseEvent.<EVT>, <handler>)}。
     * 不依赖换行/空格风格 ── ffdec 反编译输出格式偶有变化。
     */
    private static final Pattern AS3_LISTENER = Pattern.compile(
            "(\\w+)\\.addEventListener\\s*\\(\\s*MouseEvent\\.(ROLL_OVER|MOUSE_OVER|CLICK|MOUSE_DOWN|ROLL_OUT)"
                    + "\\s*,\\s*(?:this\\.)?(\\w+)");

    /** handler body 里 {@code <something>.visible = true} 的赋值。something 可含点号链。 */
    private static final Pattern HANDLER_VISIBLE_TRUE = Pattern.compile("([\\w\\.]+)\\.visible\\s*=\\s*true\\b");

    /** frame script 里 {@code <something>.visible = false} 的赋值。something 可含点号链。 */
    private static final Pattern FRAME_VISIBLE_FALSE = Pattern.compile("([\\w\\.]+)\\.visible\\s*=\\s*false\\b");

    /** 构造函数里的 {@code addFrameScript(0, this.frame1)} 绑定。 */
    private static final Pattern ADD_FRAME_SCRIPT =
            Pattern.compile("addFrameScript\\s*\\(\\s*(\\d+)\\s*,\\s*(?:this\\.)?(\\w+)\\s*\\)");

    /** handler body 里 {@code addChild(<sprite>)} 调用。 */
    private static final Pattern HANDLER_ADD_CHILD = Pattern.compile("addChild\\s*\\(\\s*(?:this\\.)?(\\w+)\\s*\\)");

    /** handler body 里 {@code gotoAndStop(N)} / {@code gotoAndPlay(N)},N 取数字。 */
    private static final Pattern HANDLER_GOTO = Pattern.compile("gotoAnd(?:Stop|Play)\\s*\\(\\s*(\\d+)\\s*\\)");

    /** handler body 里 {@code xxx.text = "字面值"} 或 {@code xxx.htmlText = "字面值"} 直接赋值。 */
    private static final Pattern HANDLER_TEXT_LITERAL =
            Pattern.compile("[\\w\\.]+\\.(?:text|htmlText)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.){2,1024})\"");

    /**
     * handler body 里 {@code xxx.text = pkg.Class.CONST} 形式的常量引用。 抽取的 group(1) 是
     * {@code pkg.Class.CONST} 整串,后续在常量池里查 simpleName。
     */
    private static final Pattern HANDLER_TEXT_CONST_REF =
            Pattern.compile("[\\w\\.]+\\.(?:text|htmlText)\\s*=\\s*((?:\\w+\\.)+\\w+)\\s*[;\\)]");

    /**
     * 类层面 {@code public static const NAME:String = "..."} / {@code var NAME:String = "..."} 声明。 抽取所有这种 const
     * 进常量池;同名 NAME 后写覆盖前写(罕见冲突)。
     */
    private static final Pattern AS_CONST_DECL = Pattern.compile(
            "(?:public|private|internal|protected)?\\s*(?:static\\s+)?(?:const|var)\\s+(\\w+)\\s*:\\s*\\w+\\s*=\\s*\"((?:[^\"\\\\]|\\\\.){0,1024})\"");

    /** 1 twip = 1/20 px。SWF 内部坐标单位是 twip,对外暴露要换成 px。 */
    private static final int TWIPS_PER_PIXEL = 20;

    private final SwfDecoderFacade decoder;

    /**
     * 扫描 SWF 反编译出的 AS 脚本,提取所有 hover / click 触发器与对应目标。
     *
     * @return 触发器 → 目标的列表,去重前的原始结果。空列表表示 SWF 里没找到 hover/click 绑定 (可能是纯展示型活动)或绑定都关联不到主舞台 instance(极端嵌套场景,待后续支持)
     */
    public InferenceResult infer(SwfDecoderFacade.ParseResult parsed) throws IOException {
        SWF swf = parsed.swf();
        Map<String, String> sources = decoder.decompileAs3(parsed);
        Map<String, PlacedInstance> instances = collectPlacedInstances(swf);
        Map<String, String> constantPool = buildConstantPool(sources);
        Set<Integer> initiallyHiddenSpriteIds = collectFrameScriptHiddenSpriteIds(sources, instances);

        List<Trigger> triggers = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            for (ListenerBinding binding : findAs3Listeners(e.getValue())) {
                PlacedInstance inst = instances.get(binding.triggerName());
                if (inst == null) {
                    log.debug(
                            "AS3 binding 找不到对应 placed instance: {} (event={}, handler={}, class={})",
                            binding.triggerName(),
                            binding.eventType(),
                            binding.handlerMethod(),
                            e.getKey());
                    continue;
                }
                Trigger.Kind kind = binding.eventType().contains("OVER") ? Trigger.Kind.HOVER : Trigger.Kind.CLICK;
                Target target =
                        extractTargetFromHandler(e.getValue(), binding.handlerMethod(), instances, constantPool);
                triggers.add(new Trigger(inst.characterId(), inst.bounds(), kind, target));
            }
        }
        int entryFrame = detectEntryFrame(sources);
        log.debug(
                "infer 输出 Trigger {} 个,来自 {} 个 AS3 类 + {} 个 placed instance + {} 条常量;入口帧 = {}",
                triggers.size(),
                sources.size(),
                instances.size(),
                constantPool.size(),
                entryFrame);
        return new InferenceResult(triggers, sources, entryFrame, initiallyHiddenSpriteIds);
    }

    /**
     * 从反编译的 AS3 类找根时间轴的入口帧。
     *
     * <p>AS3 SWF 通常一个 {@code _fla.MainTimeline extends MovieClip},构造里 addFrameScript 一个 frame1 方法 frame1
     * 里调 {@code gotoAndStop(N)} 把指针挪到真正"用户首次打开看到的"那一帧。
     *
     * <p>策略:扫所有反编译类,只看 extends MovieClip 的,找首个 {@code gotoAndStop(数字)} 的 N(1-based)。 找不到时返回 1(timeline frame 0)。
     */
    private static int detectEntryFrame(Map<String, String> sources) {
        Pattern movieClipPat = Pattern.compile("class\\s+\\w+\\s+extends\\s+(?:flash\\.display\\.)?MovieClip\\b");
        Pattern gotoPat = Pattern.compile("gotoAndStop\\s*\\(\\s*(\\d+)\\s*\\)");
        for (String src : sources.values()) {
            if (!movieClipPat.matcher(src).find()) {
                continue;
            }
            Matcher m = gotoPat.matcher(src);
            if (m.find()) {
                try {
                    int n = Integer.parseInt(m.group(1));
                    if (n >= 1) {
                        return n;
                    }
                } catch (NumberFormatException ignore) {
                    // 不至于
                }
            }
        }
        return 1;
    }

    private static List<ListenerBinding> findAs3Listeners(String source) {
        List<ListenerBinding> out = new ArrayList<>();
        Matcher m = AS3_LISTENER.matcher(source);
        while (m.find()) {
            out.add(new ListenerBinding(m.group(1), m.group(2), m.group(3)));
        }
        return out;
    }

    private static Set<Integer> collectFrameScriptHiddenSpriteIds(
            Map<String, String> sources, Map<String, PlacedInstance> instances) {
        Set<Integer> ids = new HashSet<>();
        for (String source : sources.values()) {
            Matcher frameScriptMatcher = ADD_FRAME_SCRIPT.matcher(source);
            while (frameScriptMatcher.find()) {
                String body = findHandlerBody(source, frameScriptMatcher.group(2));
                if (body.isBlank()) {
                    continue;
                }
                Matcher hiddenMatcher = FRAME_VISIBLE_FALSE.matcher(body);
                while (hiddenMatcher.find()) {
                    PlacedInstance inst = instances.get(simpleName(hiddenMatcher.group(1)));
                    if (inst != null && inst.characterId() > 0) {
                        ids.add(inst.characterId());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * 把所有 ScriptPack 里出现的 {@code const NAME:String = "字面值"} 收成 {@code NAME → 字面值} 的池。
     *
     * <p>简化处理:不区分类前缀(同名 const 在不同类极少见)。caller 用 simpleName(constRef) 查表。
     */
    private static Map<String, String> buildConstantPool(Map<String, String> sources) {
        Map<String, String> pool = new LinkedHashMap<>();
        for (String source : sources.values()) {
            Matcher m = AS_CONST_DECL.matcher(source);
            while (m.find()) {
                String name = m.group(1);
                String value = unescapeAs(m.group(2));
                if (!value.isBlank()) {
                    pool.putIfAbsent(name, value);
                }
            }
        }
        return pool;
    }

    /**
     * 从 handler method body 推断 hover/click 后展示的目标。
     *
     * <p>覆盖 AS3 模式:
     *
     * <ul>
     *   <li>{@code <sprite>.visible = true} ── 隐藏 sprite 转可见,目标 = 该 sprite 的 character id
     *   <li>{@code addChild(<sprite>)} ── 动态添加子 sprite,同上
     *   <li>{@code gotoAndStop(N) / gotoAndPlay(N)} ── 主时间轴跳帧,目标 = frameNum
     *   <li>{@code xxx.text = "字面值"} / {@code xxx.htmlText = "..."} ── tooltip 文本直接赋值
     *   <li>{@code xxx.text = ClassName.CONST} ── tooltip 文本来自常量池(buildConstantPool 预扫)
     * </ul>
     *
     * <p>handler body 找不到(method 不在反编译源码里 / 内联 lambda)或上述都不命中时返回全 null Target。
     */
    private static Target extractTargetFromHandler(
            String source,
            String handlerMethod,
            Map<String, PlacedInstance> instances,
            Map<String, String> constantPool) {
        String body = findHandlerBody(source, handlerMethod);
        if (body.isEmpty()) {
            return new Target(null, null, Target.ContentSource.TEXT_FIELD, null, List.of());
        }

        String tooltipText = extractTooltipText(body, constantPool);
        List<Integer> suppressSpriteIds = collectDisplayedSpriteIds(body, instances);

        Matcher mVis = HANDLER_VISIBLE_TRUE.matcher(body);
        if (mVis.find()) {
            PlacedInstance inst = instances.get(simpleName(mVis.group(1)));
            if (inst != null) {
                return new Target(
                        inst.characterId(), null, Target.ContentSource.TEXT_FIELD, tooltipText, suppressSpriteIds);
            }
        }
        Matcher mAdd = HANDLER_ADD_CHILD.matcher(body);
        if (mAdd.find()) {
            PlacedInstance inst = instances.get(simpleName(mAdd.group(1)));
            if (inst != null) {
                return new Target(
                        inst.characterId(), null, Target.ContentSource.TEXT_FIELD, tooltipText, suppressSpriteIds);
            }
        }
        Matcher mGoto = HANDLER_GOTO.matcher(body);
        if (mGoto.find()) {
            try {
                return new Target(
                        null,
                        Integer.parseInt(mGoto.group(1)),
                        Target.ContentSource.TEXT_FIELD,
                        tooltipText,
                        suppressSpriteIds);
            } catch (NumberFormatException ignore) {
                // gotoAndStop("frameLabel") 字符串标签场景,当前不解析
            }
        }
        return new Target(null, null, Target.ContentSource.TEXT_FIELD, tooltipText, suppressSpriteIds);
    }

    /**
     * handler 里可能一次打开多个视觉层(背景框、文本、按钮态)。主渲染 suppress 时需要全部隐藏,否则原版 tooltip
     * 会残留在主页上。
     */
    private static List<Integer> collectDisplayedSpriteIds(String body, Map<String, PlacedInstance> instances) {
        List<Integer> ids = new ArrayList<>();
        Matcher mVis = HANDLER_VISIBLE_TRUE.matcher(body);
        while (mVis.find()) {
            addSuppressId(ids, instances.get(simpleName(mVis.group(1))));
        }
        Matcher mAdd = HANDLER_ADD_CHILD.matcher(body);
        while (mAdd.find()) {
            addSuppressId(ids, instances.get(simpleName(mAdd.group(1))));
        }
        return ids;
    }

    private static void addSuppressId(List<Integer> ids, PlacedInstance inst) {
        if (inst == null || inst.characterId() <= 0 || ids.contains(inst.characterId())) {
            return;
        }
        ids.add(inst.characterId());
    }

    /**
     * 在 handler body 里找 {@code xxx.text = ...} 赋值的内容。 字面值优先;然后 const 引用查池。找不到返回 null。
     */
    private static String extractTooltipText(String body, Map<String, String> constantPool) {
        Matcher mLit = HANDLER_TEXT_LITERAL.matcher(body);
        if (mLit.find()) {
            return unescapeAs(mLit.group(1));
        }
        Matcher mRef = HANDLER_TEXT_CONST_REF.matcher(body);
        if (mRef.find()) {
            String constName = simpleName(mRef.group(1));
            String value = constantPool.get(constName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String unescapeAs(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 在反编译源码里定位 {@code function <handlerMethod>(...) { ... }} 的 body。 简单括号配平,处理嵌套大括号。找不到返回空字符串。
     */
    private static String findHandlerBody(String source, String handlerMethod) {
        Pattern start = Pattern.compile("function\\s+" + Pattern.quote(handlerMethod) + "\\s*\\(");
        Matcher m = start.matcher(source);
        if (!m.find()) {
            return "";
        }
        int idx = source.indexOf('{', m.end());
        if (idx < 0) {
            return "";
        }
        int depth = 1;
        int end = idx + 1;
        int len = source.length();
        while (end < len && depth > 0) {
            char c = source.charAt(end);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            end++;
        }
        return depth == 0 ? source.substring(idx + 1, end - 1) : "";
    }

    /** 取最后一个点之后的 simple name:{@code this.tooltip} → {@code tooltip}。 */
    private static String simpleName(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    /**
     * 扫主舞台 + 所有 DefineSprite 子 timeline 找有 instanceName 的 PlaceObject。 同名 instance 出现在多个地方时(例如多个 frame
     * 都 place 同一个 helpBtn), 取首次出现的 placement ── 它通常就是稳定显示的版本。
     */
    private static Map<String, PlacedInstance> collectPlacedInstances(SWF swf) {
        Map<String, PlacedInstance> map = new LinkedHashMap<>();
        recordPlaces(swf.getTags(), swf, map);
        for (CharacterTag c : swf.getCharacters(false).values()) {
            if (c instanceof DefineSpriteTag spr) {
                recordPlaces(spr.getTags(), swf, map);
            }
        }
        return map;
    }

    private static void recordPlaces(ReadOnlyTagList tags, SWF swf, Map<String, PlacedInstance> map) {
        for (Tag tag : tags) {
            if (!(tag instanceof PlaceObjectTypeTag place)) {
                continue;
            }
            String name = place.getInstanceName();
            if (name == null || name.isBlank() || map.containsKey(name)) {
                continue;
            }
            int characterId = extractCharacterId(place);
            if (characterId <= 0) {
                continue; // 没有 character id 的 PlaceObject 多半是 move-only 更新,跳过
            }
            CharacterTag character = swf.getCharacter(characterId);
            DisplayBounds bounds = computeBounds(place.getMatrix(), character);
            map.put(name, new PlacedInstance(name, characterId, bounds));
        }
    }

    /**
     * PlaceObjectTypeTag 没暴露 getCharacterId 通用接口。PlaceObject2/3/4 都继承 PlaceObject2Tag(PlaceObject3/4 是 superset),故
     * instanceof PlaceObject2Tag 可覆盖 2/3/4。PlaceObject1 走 PlaceObjectTag 自己的 getCharacterId()。
     */
    private static int extractCharacterId(PlaceObjectTypeTag place) {
        if (place instanceof PlaceObject2Tag p2) {
            return p2.placeFlagHasCharacter ? p2.characterId : -1;
        }
        if (place instanceof PlaceObjectTag p1) {
            return p1.getCharacterId();
        }
        return -1;
    }

    /**
     * 计算 character 在主舞台坐标系下的 bounds(px)。所有 character 类型都通过 BoundedTag 接口拿 getRect ── DefineSprite /
     * DefineButton / DefineButton2 / DefineEditText / DefineShape{1-4} / DefineMorphShape 等都实现 BoundedTag。
     */
    private static DisplayBounds computeBounds(MATRIX matrix, CharacterTag character) {
        int xPx = matrix == null ? 0 : matrix.translateX / TWIPS_PER_PIXEL;
        int yPx = matrix == null ? 0 : matrix.translateY / TWIPS_PER_PIXEL;
        int wPx = 0;
        int hPx = 0;
        if (character instanceof BoundedTag bounded) {
            try {
                RECT rect = bounded.getRect(new HashSet<>());
                if (rect != null) {
                    wPx = rect.getWidth() / TWIPS_PER_PIXEL;
                    hPx = rect.getHeight() / TWIPS_PER_PIXEL;
                }
            } catch (Exception e) {
                log.debug("character bounds 计算失败,使用 0x0: {}", e.getMessage());
            }
        }
        return new DisplayBounds(xPx, yPx, wPx, hPx);
    }

    /** 触发器(被悬浮或点击的可视元素)的位置 / 类型 / 对应目标。 */
    public record Trigger(int spriteId, DisplayBounds bounds, Kind kind, Target target) {

        public enum Kind {
            HOVER,
            CLICK
        }
    }

    /** 触发器在主舞台坐标系下的包围盒(px)。 */
    public record DisplayBounds(int x, int y, int width, int height) {}

    /**
     * 触发后展示的目标内容。
     *
     * <ul>
     *   <li>spriteId 非空:目标是隐藏 sprite,运行时 setVisible(true) 或 addChild
     *   <li>frameNum 非空:目标是主时间轴跳转 gotoAndStop(N)
     *   <li>tooltipText 非空:handler body 显式赋值的文本(优于 round-robin 拿池里轮询)
     *   <li>suppressSpriteIds:handler 打开的全部 sprite,用于主页隐藏原版 tooltip / 动态面板
     * </ul>
     */
    public record Target(
            Integer spriteId,
            Integer frameNum,
            ContentSource contentSource,
            String tooltipText,
            List<Integer> suppressSpriteIds) {

        public enum ContentSource {
            TEXT_FIELD,
            DEFAULT_TEXT,
            I18N_KEY
        }
    }

    /** 内部:从 AS3 源码 regex 抓到的一条 listener 绑定。 */
    private record ListenerBinding(String triggerName, String eventType, String handlerMethod) {}

    /** 内部:主舞台或子 sprite 上一个有 instanceName 的 PlaceObject。 */
    private record PlacedInstance(String name, int characterId, DisplayBounds bounds) {}

    /**
     * {@link #infer} 的复合返回:trigger 列表 + 反编译源码集合 + 入口帧号。
     *
     * @param triggers 推断出的 hover / click 触发器
     * @param decompiledSources className → AS3 源码,用于 caller 进一步分析(通常 caller 不需要)
     * @param entryFrame 推断出的入口帧 (1-based)。1 表示就用 timeline frame 0
     */
    public record InferenceResult(
            List<Trigger> triggers,
            Map<String, String> decompiledSources,
            int entryFrame,
            Set<Integer> initiallyHiddenSpriteIds) {}
}
