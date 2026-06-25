package com.strikegod.engine.gateway.game.activity.swf;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用 Java2D 重画一个现代风格 tooltip 卡片。
 *
 * <p>提供两种渲染模式:
 *
 * <ol>
 *   <li>{@link #composeOverlay} ── 透明画布上,贴在 trigger 附近(给小型 tooltip 用,叠在主页上 &lt; 20% 覆盖)
 *   <li>{@link #composeStandalone} ── 整张干净画布上居中渲染一个大卡片(给中 / 大型 tooltip 单独成页)
 * </ol>
 *
 * <p>处理:
 *
 * <ul>
 *   <li>HTML 实体解码 (&amp;nbsp; / &amp;amp; / &amp;lt; / &amp;gt; / &amp;quot; / &amp;#39; / &amp;#NNN; / U+00A0 等)
 *   <li>CJK 友好换行(空格优先,任意字符 fallback)
 *   <li>多空格 / Tab 折叠成单空格
 * </ul>
 */
@Slf4j
@Component
public class TooltipComposer {

    /** 文本长度阈值:小型 / 中型 / 大型 tooltip 的分界。 */
    public static final int SMALL_MAX_CHARS = 30;

    public static final int MEDIUM_MAX_CHARS = 80;

    private static final int OVERLAY_PAD_X = 14;
    private static final int OVERLAY_PAD_Y = 10;
    private static final int OVERLAY_CORNER = 10;
    private static final int OVERLAY_FONT_SIZE = 14;
    private static final int OVERLAY_LINE_GAP = 3;
    private static final int OVERLAY_MAX_W = 280;
    private static final int OVERLAY_EDGE_MARGIN = 8;
    private static final int OVERLAY_LEADER_GAP = 10;

    private static final int STANDALONE_PAD_X = 36;
    private static final int STANDALONE_PAD_Y = 28;
    private static final int STANDALONE_CORNER = 16;
    private static final int STANDALONE_FONT_SIZE = 22;
    private static final int STANDALONE_LINE_GAP = 8;

    private static final Color OVERLAY_BG = new Color(20, 26, 40, 235);
    private static final Color OVERLAY_BORDER = new Color(255, 212, 0, 200);
    private static final Color OVERLAY_TEXT = new Color(245, 245, 245);
    private static final Color OVERLAY_LINE = new Color(255, 212, 0, 180);
    private static final Color OVERLAY_SHADOW = new Color(0, 0, 0, 80);

    private static final Color STANDALONE_BG = new Color(255, 255, 255);
    private static final Color STANDALONE_BORDER = new Color(64, 120, 242, 200);
    private static final Color STANDALONE_TEXT = new Color(56, 58, 66);
    private static final Color STANDALONE_TITLE = new Color(64, 120, 242);

    private static final Pattern HTML_NUMERIC = Pattern.compile("&#(\\d+);");
    private static final Pattern HTML_HEX = Pattern.compile("&#[xX]([0-9a-fA-F]+);");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t]+");
    private static final Pattern NUMBERED_LIST_BREAK = Pattern.compile("(?<=[^\\d\\n])(\\d{1,2}\\.)\\s*(?=\\S)");
    private static final Pattern COLON_BREAK = Pattern.compile("(?<=[^\\d\\s])([:：])\\s*(?=\\S)");
    private static final Pattern SENTENCE_END_BREAK = Pattern.compile("([。!！?？;；])\\s*(?=[^\\s\\n])");
    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\\n{3,}");

    /** 文本规模分类。 */
    public enum Size {
        SMALL,
        MEDIUM,
        LARGE
    }

    /** 文本规模分类:按 cleanText 后字符长度。 */
    public static Size classify(String text) {
        String cleaned = cleanText(text);
        int n = cleaned.length();
        if (n <= SMALL_MAX_CHARS) return Size.SMALL;
        if (n <= MEDIUM_MAX_CHARS) return Size.MEDIUM;
        return Size.LARGE;
    }

    /**
     * 在 {@code canvasWidth × canvasHeight} 透明画布上,围绕 trigger 位置画一个 tooltip(小型用,< 20% 覆盖主页)。
     *
     * @param canvasWidth 主舞台宽 (px),等同 SWF.displayRect 的宽除 20
     * @param canvasHeight 主舞台高
     * @param triggerX trigger 在主舞台坐标系下的左上 x
     * @param triggerY trigger 在主舞台坐标系下的左上 y
     * @param triggerW trigger 宽
     * @param triggerH trigger 高
     * @param tooltipText 要展示的文本(进入前会做 HTML 实体清理)
     * @return ARGB BufferedImage,透明背景 + tooltip 元素
     */
    public BufferedImage composeOverlay(
            int canvasWidth,
            int canvasHeight,
            int triggerX,
            int triggerY,
            int triggerW,
            int triggerH,
            String tooltipText) {
        String text = cleanText(tooltipText);
        BufferedImage img = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            applyHints(g);

            Font font = pickFont(OVERLAY_FONT_SIZE);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            int innerWidth = Math.min(canvasWidth - 2 * OVERLAY_EDGE_MARGIN, OVERLAY_MAX_W) - 2 * OVERLAY_PAD_X;
            List<String> lines = wrapText(text, fm, Math.max(40, innerWidth));
            int textWidth = lines.stream().mapToInt(fm::stringWidth).max().orElse(80);
            int lineHeight = fm.getHeight() + OVERLAY_LINE_GAP;
            int textHeight = lines.size() * lineHeight - OVERLAY_LINE_GAP;
            int tooltipW = Math.min(OVERLAY_MAX_W, textWidth + 2 * OVERLAY_PAD_X);
            int tooltipH = textHeight + 2 * OVERLAY_PAD_Y;

            int triggerCx = triggerX + triggerW / 2;
            int triggerCy = triggerY + triggerH / 2;
            int tooltipX =
                    clamp(triggerCx - tooltipW / 2, OVERLAY_EDGE_MARGIN, canvasWidth - tooltipW - OVERLAY_EDGE_MARGIN);
            boolean above = triggerY > tooltipH + OVERLAY_LEADER_GAP + OVERLAY_EDGE_MARGIN;
            int tooltipY = above ? triggerY - tooltipH - OVERLAY_LEADER_GAP : triggerY + triggerH + OVERLAY_LEADER_GAP;
            tooltipY = clamp(tooltipY, OVERLAY_EDGE_MARGIN, canvasHeight - tooltipH - OVERLAY_EDGE_MARGIN);

            // 引导线
            int lineFromX = clamp(triggerCx, tooltipX + OVERLAY_CORNER, tooltipX + tooltipW - OVERLAY_CORNER);
            int lineFromY = above ? tooltipY + tooltipH : tooltipY;
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(OVERLAY_LINE);
            g.drawLine(lineFromX, lineFromY, triggerCx, triggerCy);

            // 阴影
            g.setColor(OVERLAY_SHADOW);
            g.fill(new RoundRectangle2D.Float(
                    tooltipX, tooltipY + 3, tooltipW, tooltipH, OVERLAY_CORNER, OVERLAY_CORNER));

            // 背景 + 边框
            g.setColor(OVERLAY_BG);
            g.fill(new RoundRectangle2D.Float(tooltipX, tooltipY, tooltipW, tooltipH, OVERLAY_CORNER, OVERLAY_CORNER));
            g.setColor(OVERLAY_BORDER);
            g.setStroke(new BasicStroke(1.0f));
            g.draw(new RoundRectangle2D.Float(tooltipX, tooltipY, tooltipW, tooltipH, OVERLAY_CORNER, OVERLAY_CORNER));

            // 文本
            g.setColor(OVERLAY_TEXT);
            int textX = tooltipX + OVERLAY_PAD_X;
            int textY = tooltipY + OVERLAY_PAD_Y + fm.getAscent();
            for (String line : lines) {
                g.drawString(line, textX, textY);
                textY += lineHeight;
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * 在干净画布上居中渲染一个大 tooltip 卡片(中 / 大型用,作为 PDF 单独一页)。
     *
     * @param canvasWidth 画布宽 (一般等同主舞台宽)
     * @param canvasHeight 画布高
     * @param tooltipText 要展示的文本(进入前会做 HTML 实体清理)
     * @param caption 顶部小标题(可空,例如 "trigger#62 hover 提示")
     */
    public BufferedImage composeStandalone(int canvasWidth, int canvasHeight, String tooltipText, String caption) {
        return composeStandalone(canvasWidth, canvasHeight, tooltipText);
    }

    /**
     * 在干净画布上居中渲染一个大 tooltip 卡片(中 / 大型用,作为 PDF 单独一页)。
     *
     * @param canvasWidth 画布宽 (一般等同主舞台宽)
     * @param canvasHeight 画布高
     * @param tooltipText 要展示的文本(进入前会做 HTML 实体清理)
     */
    public BufferedImage composeStandalone(int canvasWidth, int canvasHeight, String tooltipText) {
        String text = cleanText(tooltipText);
        BufferedImage img = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            applyHints(g);

            // 浅色背景
            g.setColor(new Color(248, 250, 254));
            g.fillRect(0, 0, canvasWidth, canvasHeight);

            Font font = pickFont(STANDALONE_FONT_SIZE);

            // 计算卡片尺寸:留 80 px 边距,文本宽度 max 700
            int sideMargin = Math.max(40, canvasWidth / 12);
            int cardMaxW = Math.min(canvasWidth - 2 * sideMargin, 760);
            int innerW = cardMaxW - 2 * STANDALONE_PAD_X;

            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrapText(text, fm, innerW);
            int lineH = fm.getHeight() + STANDALONE_LINE_GAP;
            int textBlockH = lines.size() * lineH - STANDALONE_LINE_GAP;

            int cardH = textBlockH + 2 * STANDALONE_PAD_Y;
            int cardX = (canvasWidth - cardMaxW) / 2;
            int cardY = Math.max(40, (canvasHeight - cardH) / 2);

            // 卡片阴影
            g.setColor(new Color(0, 0, 0, 30));
            g.fill(new RoundRectangle2D.Float(
                    cardX + 4, cardY + 6, cardMaxW, cardH, STANDALONE_CORNER, STANDALONE_CORNER));

            // 卡片
            g.setColor(STANDALONE_BG);
            g.fill(new RoundRectangle2D.Float(cardX, cardY, cardMaxW, cardH, STANDALONE_CORNER, STANDALONE_CORNER));
            g.setColor(STANDALONE_BORDER);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(cardX, cardY, cardMaxW, cardH, STANDALONE_CORNER, STANDALONE_CORNER));

            int yCursor = cardY + STANDALONE_PAD_Y;

            // 文本
            g.setFont(font);
            g.setColor(STANDALONE_TEXT);
            yCursor += fm.getAscent();
            for (String line : lines) {
                g.drawString(line, cardX + STANDALONE_PAD_X, yCursor);
                yCursor += lineH;
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * 全套清理:HTML 标签 + 命名实体 + 数字字符引用 + U+00A0 / U+3000 → 空格 + 多空格折叠。
     *
     * <p>如果输入只有数字 / 占位短串,可能会被剥得很短;caller 自行决定是否替换为占位文本。
     */
    public static String cleanText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String s = stripHtmlTags(value);
        s = HTML_NUMERIC.matcher(s).replaceAll(m -> safeChar(Integer.parseInt(m.group(1))));
        s = HTML_HEX.matcher(s).replaceAll(m -> safeChar(Integer.parseInt(m.group(1), 16)));
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&hellip;", "…")
                .replace("&middot;", "·")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace(" ", " ") // non-breaking space
                .replace("　", " "); // CJK 全角空格
        // 折叠多空格 / Tab → 单空格(保留 \n)
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        // 清理每行首尾空格但保留换行
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.strip());
        }
        return sb.toString();
    }

    private static String safeChar(int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF) return "";
        return new String(Character.toChars(codePoint));
    }

    private static String stripHtmlTags(String value) {
        if (value.indexOf('<') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        boolean inTag = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                inTag = true;
                continue;
            }
            if (c == '>') {
                inTag = false;
                continue;
            }
            if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static Font pickFont(int size) {
        for (String name : new String[] {"Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC"}) {
            Font f = new Font(name, Font.PLAIN, size);
            if (!"Dialog".equals(f.getFamily())) {
                return f;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    /**
     * 折行入口:先 normalize 把数字编号 / 句末标点 / 冒号转成显式 \n,再按段落分别 wrap。
     */
    static List<String> wrapText(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String normalized = normalizeForWrap(text);
        for (String paragraph : normalized.split("\n", -1)) {
            wrapParagraph(paragraph, fm, maxWidth, lines);
        }
        return lines;
    }

    /** 在数字编号前 / 冒号后 / 中文句末标点后插入 {@code \n},让长段先按语义切开。 */
    static String normalizeForWrap(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String result = NUMBERED_LIST_BREAK.matcher(s).replaceAll("\n$1 ");
        result = COLON_BREAK.matcher(result).replaceAll("$1\n");
        result = SENTENCE_END_BREAK.matcher(result).replaceAll("$1\n");
        result = EXCESS_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.strip();
    }

    /** 单段折行。空格与 CJK 标点优先,任意 CJK 字符兜底。 */
    private static void wrapParagraph(String text, FontMetrics fm, int maxWidth, List<String> lines) {
        if (text.isEmpty()) {
            lines.add("");
            return;
        }
        StringBuilder cur = new StringBuilder();
        int lastStrongBreak = -1;
        int lastWeakBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (isStrongBreakAfter(c)) {
                lastStrongBreak = cur.length() - 1;
            } else if (isWeakBreakAfter(c)) {
                lastWeakBreak = cur.length() - 1;
            }
            if (fm.stringWidth(cur.toString()) > maxWidth) {
                int breakAt = lastStrongBreak > 0 ? lastStrongBreak + 1 : lastWeakBreak > 0 ? lastWeakBreak + 1 : -1;
                if (breakAt > 0 && breakAt < cur.length()) {
                    String line = cur.substring(0, breakAt).stripTrailing();
                    String rest = cur.substring(breakAt).stripLeading();
                    lines.add(line);
                    cur.setLength(0);
                    cur.append(rest);
                    lastStrongBreak = -1;
                    lastWeakBreak = -1;
                } else if (cur.length() > 1) {
                    char last = cur.charAt(cur.length() - 1);
                    cur.setLength(cur.length() - 1);
                    lines.add(cur.toString());
                    cur.setLength(0);
                    cur.append(last);
                    lastStrongBreak = -1;
                    lastWeakBreak = -1;
                }
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
    }

    private static boolean isStrongBreakAfter(char c) {
        if (c == ' ' || c == '\t') {
            return true;
        }
        return ",.;:!?，。、；：！？”’」』)]}）】>".indexOf(c) >= 0;
    }

    private static boolean isWeakBreakAfter(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        if (ub == null) {
            return false;
        }
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.HIRAGANA
                || ub == Character.UnicodeBlock.KATAKANA
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
