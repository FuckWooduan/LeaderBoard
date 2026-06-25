package com.strikegod.engine.gateway.game.activity.swf;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 把 {@link ActivityRenderingPipeline.RenderingResult} 持久化为「主帧 PNG + 结构化 PDF」。
 *
 * <pre>
 *   &lt;outputDir&gt;/
 *   ├── main.png
 *   ├── report.png
 *   ├── report.pdf
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityReportBuilder {

    private static final int PAGE_SIDE_PAD = 24;
    private static final int SECTION_GAP = 18;
    private static final int CARD_PAD_X = 22;
    private static final int CARD_PAD_Y = 16;
    private static final int CARD_GAP = 12;
    private static final int CARD_RADIUS = 8;
    private static final int ITEM_GRID_COLUMNS = 5;
    private static final int ITEM_GRID_MIN_COUNT = 3;
    private static final int ITEM_CELL_GAP = 8;
    private static final int ITEM_CELL_PAD_X = 8;
    private static final int ITEM_CELL_PAD_Y = 7;
    private static final Color PAGE_BG = new Color(12, 13, 18);
    private static final Color CARD_BG = new Color(28, 31, 38);
    private static final Color CARD_BORDER = new Color(222, 170, 46);
    private static final Color CARD_TEXT = new Color(245, 241, 224);
    private static final Color TEXT_BOX_BG = new Color(19, 22, 28);
    private static final Color TEXT_BOX_BORDER = new Color(96, 105, 120);
    private static final Color MUTED_TEXT = new Color(182, 191, 206);
    private static final Font CARD_FONT = new Font("Microsoft YaHei", Font.PLAIN, 16);
    private static final Font ACTIVITY_DETAIL_FONT = new Font("Microsoft YaHei", Font.PLAIN, 14);
    private static final Font PREFORMATTED_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 15);
    private static final int PREFORMATTED_COLUMN_GAP = 52;
    private static final double PREFORMATTED_COLUMN_RATIO = 0.42;
    private static final Font HEADER_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 28);
    private static final Font HEADER_BODY_FONT = new Font("Microsoft YaHei", Font.PLAIN, 17);
    private static final Font HEADER_SOURCE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 15);
    private static final String WATERMARK_TITLE = "StrikeGod 活动预览图";
    private static final String WATERMARK_SUBTITLE = "自动解析活动 | 高清还原 | example.com";
    private static final int WATERMARK_PAD_X = 28;
    private static final int WATERMARK_PAD_Y = 10;
    private static final int WATERMARK_GAP = 4;
    private static final int WATERMARK_MIN_HEIGHT = 76;
    private static final Color WATERMARK_BG = new Color(17, 20, 27);
    private static final Color WATERMARK_BORDER = new Color(213, 179, 78);
    private static final Color WATERMARK_TITLE_TEXT = new Color(250, 244, 224);
    private static final Color WATERMARK_BODY_TEXT = new Color(190, 199, 214);
    private static final Font WATERMARK_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 20);
    private static final Font WATERMARK_BODY_FONT = new Font("Microsoft YaHei", Font.PLAIN, 16);
    private static final Pattern REWARD_ITEM_PATTERN = Pattern.compile(
            "([^\\s*，,、；;]+?(?:\\*\\d+(?:~\\d+)?(?:天|个|次|张|枚|份|套|点|颗|件|把|包|小时|分钟)?|[（(][^）)]*(?:永久|租赁|无磨损度)[^）)]*[）)](?:\\*\\d+(?:~\\d+)?)?))");

    public ReportPaths writeAll(ActivityRenderingPipeline.RenderingResult result, String activityTitle, Path outputDir)
            throws IOException {
        return writeAll(List.of(result), activityTitle, outputDir);
    }

    public ReportPaths writeAll(
            ActivityRenderingPipeline.RenderingResult result,
            String activityTitle,
            Path outputDir,
            boolean showOriginalFileName)
            throws IOException {
        return writeAll(List.of(result), activityTitle, outputDir, new ReportOptions(showOriginalFileName));
    }

    public ReportPaths writeAll(
            List<ActivityRenderingPipeline.RenderingResult> results, String activityTitle, Path outputDir)
            throws IOException {
        return writeAll(results, activityTitle, outputDir, ReportOptions.production());
    }

    public ReportPaths writeAll(
            List<ActivityRenderingPipeline.RenderingResult> results,
            String activityTitle,
            Path outputDir,
            boolean showOriginalFileName)
            throws IOException {
        return writeAll(results, activityTitle, outputDir, new ReportOptions(showOriginalFileName));
    }

    public ReportPaths writeAll(
            List<ActivityRenderingPipeline.RenderingResult> results,
            String activityTitle,
            Path outputDir,
            ReportOptions options)
            throws IOException {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("活动报告至少需要一个渲染结果");
        }
        ReportOptions resolvedOptions = options == null ? ReportOptions.production() : options;
        Files.createDirectories(outputDir);
        cleanupGeneratedArtifacts(outputDir);

        Path mainPath = outputDir.resolve("main.png");
        BufferedImage main = results.get(0).mainFrame();
        writePng(addWatermark(main), mainPath);

        BufferedImage reportImage = addWatermark(composePage(results, resolvedOptions));
        Path reportPngPath = outputDir.resolve("report.png");
        writePng(reportImage, reportPngPath);

        Path pdfPath = outputDir.resolve("report.pdf");
        try (OutputStream pdfOut = Files.newOutputStream(pdfPath)) {
            buildPdf(composePdfPages(results, resolvedOptions), activityTitle, pdfOut);
        } catch (RuntimeException ex) {
            log.warn("ActivityReportBuilder PDF 生成失败: {}", ex.getMessage(), ex);
            Files.deleteIfExists(pdfPath);
            pdfPath = null;
        }

        log.info(
                "ActivityReportBuilder 完成: dir={} main=1 reportPng=1 defineSprite={} click={} hoverS={} hoverM={} hoverL={} warn={} pdf={}",
                outputDir.toAbsolutePath(),
                results.stream().mapToInt(r -> r.defineSprites().size()).sum(),
                results.stream().mapToInt(r -> r.clickFrames().size()).sum(),
                results.stream().mapToInt(r -> r.hoverSmall().size()).sum(),
                results.stream().mapToInt(r -> r.hoverMedium().size()).sum(),
                results.stream().mapToInt(r -> r.hoverLarge().size()).sum(),
                results.stream().mapToInt(r -> r.warnings().size()).sum(),
                pdfPath != null);
        return new ReportPaths(mainPath, pdfPath, reportPngPath);
    }

    private void buildPdf(List<BufferedImage> pageImages, String title, OutputStream out) throws IOException {
        PdfWriter writer = new PdfWriter(out);
        try (PdfDocument pdf = new PdfDocument(writer)) {
            pdf.getDocumentInfo().setTitle(title);
            for (BufferedImage image : pageImages) {
                addPage(pdf, image);
            }
        }
    }

    private static BufferedImage addWatermark(BufferedImage image) {
        int width = image.getWidth();
        int maxTextW = Math.max(120, width - WATERMARK_PAD_X * 2);
        BufferedImage scratch = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D measure = scratch.createGraphics();
        List<String> subtitleLines;
        FontMetrics titleFm;
        FontMetrics bodyFm;
        try {
            applyHints(measure);
            measure.setFont(WATERMARK_TITLE_FONT);
            titleFm = measure.getFontMetrics();
            measure.setFont(WATERMARK_BODY_FONT);
            bodyFm = measure.getFontMetrics();
            subtitleLines = wrapWatermarkText(WATERMARK_SUBTITLE, bodyFm, maxTextW);
        } finally {
            measure.dispose();
        }

        int titleH = titleFm.getHeight();
        int bodyLineH = bodyFm.getHeight() + 2;
        int textH = titleH + WATERMARK_GAP + Math.max(1, subtitleLines.size()) * bodyLineH;
        int bannerH = Math.max(WATERMARK_MIN_HEIGHT, textH + WATERMARK_PAD_Y * 2);
        BufferedImage out = new BufferedImage(width, image.getHeight() + bannerH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            applyHints(g);
            g.setColor(PAGE_BG);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(image, 0, 0, null);
            int bannerY = image.getHeight();
            g.setColor(WATERMARK_BG);
            g.fillRect(0, bannerY, width, bannerH);
            g.setColor(WATERMARK_BORDER);
            g.fillRect(0, bannerY, width, 3);

            int y = bannerY + Math.max(WATERMARK_PAD_Y, (bannerH - textH) / 2) + titleFm.getAscent();
            g.setFont(WATERMARK_TITLE_FONT);
            g.setColor(WATERMARK_TITLE_TEXT);
            drawCenteredString(g, WATERMARK_TITLE, width, y);

            y += titleFm.getDescent() + WATERMARK_GAP + bodyFm.getAscent();
            g.setFont(WATERMARK_BODY_FONT);
            g.setColor(WATERMARK_BODY_TEXT);
            for (String line : subtitleLines.isEmpty() ? List.of(WATERMARK_SUBTITLE) : subtitleLines) {
                drawCenteredString(g, line, width, y);
                y += bodyLineH;
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static List<String> wrapWatermarkText(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (" ，,、;；".indexOf(c) >= 0) {
                lastBreak = current.length();
            }
            if (fm.stringWidth(current.toString()) > maxWidth && current.length() > 1) {
                int breakAt = lastBreak > 0 ? lastBreak : current.length() - 1;
                lines.add(current.substring(0, breakAt).strip());
                String rest = current.substring(breakAt).stripLeading();
                current.setLength(0);
                current.append(rest);
                lastBreak = -1;
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString().strip());
        }
        return lines;
    }

    private static void drawCenteredString(Graphics2D g, String text, int width, int baselineY) {
        FontMetrics fm = g.getFontMetrics();
        int x = Math.max(WATERMARK_PAD_X, (width - fm.stringWidth(text)) / 2);
        g.drawString(text, x, baselineY);
    }

    private static BufferedImage composeSinglePage(ActivityRenderingPipeline.RenderingResult result) {
        return composePage(List.of(result), ReportOptions.production());
    }

    private static ReportLayout measureReportLayout(
            List<ActivityRenderingPipeline.RenderingResult> results, ReportOptions options) {
        List<ReportInput> inputs = toReportInputs(results, options);
        ReportInput first = inputs.get(0);
        int maxMainW = inputs.stream()
                .map(input -> input.result().mainFrame())
                .mapToInt(BufferedImage::getWidth)
                .max()
                .orElse(720);
        int pageW = Math.max(maxMainW + PAGE_SIDE_PAD * 2, 720);
        int contentW = pageW - PAGE_SIDE_PAD * 2;
        HeaderLayout header = measureHeader(
                first.result(), originalFileNamesForHeader(inputs), options.showOriginalFileName(), contentW);
        List<PageBlock> blocks = new ArrayList<>();
        Set<String> seenTextCards = new HashSet<>();
        for (ReportInput input : inputs) {
            ActivityRenderingPipeline.RenderingResult result = input.result();
            BufferedImage main = result.mainFrame();
            blocks.add(new PageBlock(main, null, main.getHeight()));
            for (CardLayout card : measureContentCards(result, contentW)) {
                if (card.image() == null) {
                    String signature = textSignature(cardText(card));
                    if (!signature.isBlank() && !seenTextCards.add(signature)) {
                        continue;
                    }
                }
                blocks.add(new PageBlock(null, card, card.height()));
            }
        }
        return new ReportLayout(pageW, contentW, header, blocks);
    }

    private static String originalFileNamesForHeader(List<ReportInput> inputs) {
        return inputs.stream()
                .map(ReportInput::originalFileName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static BufferedImage composePage(
            List<ActivityRenderingPipeline.RenderingResult> results, ReportOptions options) {
        ReportLayout layout = measureReportLayout(results, options);
        int pageW = layout.pageW();
        int contentW = layout.contentW();
        HeaderLayout header = layout.header();
        List<PageBlock> blocks = layout.blocks();
        int pageH = PAGE_SIDE_PAD + header.height() + SECTION_GAP;
        for (PageBlock block : blocks) {
            pageH += block.height() + SECTION_GAP;
        }
        if (!blocks.isEmpty()) {
            pageH -= SECTION_GAP;
        }
        pageH += PAGE_SIDE_PAD;

        BufferedImage out = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            applyHints(g);
            g.setColor(PAGE_BG);
            g.fillRect(0, 0, pageW, pageH);
            drawHeader(g, header, PAGE_SIDE_PAD, PAGE_SIDE_PAD, contentW);
            int y = PAGE_SIDE_PAD + header.height() + SECTION_GAP;
            for (PageBlock block : blocks) {
                if (block.mainImage() != null) {
                    g.drawImage(block.mainImage(), (pageW - block.mainImage().getWidth()) / 2, y, null);
                } else {
                    drawCard(g, block.card(), PAGE_SIDE_PAD, y, contentW);
                }
                y += block.height() + SECTION_GAP;
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static List<BufferedImage> composePdfPages(
            List<ActivityRenderingPipeline.RenderingResult> results, ReportOptions options) {
        ReportLayout layout = measureReportLayout(results, options);
        return List.of(composePdfPage(layout, layout.blocks(), true));
    }

    private static List<ReportInput> toReportInputs(
            List<ActivityRenderingPipeline.RenderingResult> results, ReportOptions options) {
        ReportOptions resolvedOptions = options == null ? ReportOptions.production() : options;
        List<String> fileNames =
                resolvedOptions.originalFileNames() == null ? List.of() : resolvedOptions.originalFileNames();
        List<ReportInput> inputs = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            ActivityRenderingPipeline.RenderingResult result = results.get(i);
            String configuredName = i < fileNames.size() ? fileNames.get(i) : null;
            inputs.add(new ReportInput(result, resolveOriginalFileName(result, configuredName)));
        }
        return inputs;
    }

    private static String resolveOriginalFileName(
            ActivityRenderingPipeline.RenderingResult result, String configuredName) {
        if (configuredName != null && !configuredName.isBlank()) {
            return stripPath(configuredName);
        }
        if (result.metadata() == null
                || result.metadata().url() == null
                || result.metadata().url().isBlank()) {
            return "";
        }
        return result.metadata().url() + ".swf";
    }

    private static String stripPath(String fileName) {
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static int basePdfPageHeight(HeaderLayout header, boolean includeHeader) {
        return PAGE_SIDE_PAD + (includeHeader ? header.height() + SECTION_GAP : 0);
    }

    private static BufferedImage composePdfPage(ReportLayout layout, List<PageBlock> blocks, boolean includeHeader) {
        int pageW = layout.pageW();
        int contentW = layout.contentW();
        int pageH = basePdfPageHeight(layout.header(), includeHeader);
        for (int i = 0; i < blocks.size(); i++) {
            pageH += (i == 0 ? 0 : SECTION_GAP) + blocks.get(i).height();
        }
        pageH += PAGE_SIDE_PAD;
        BufferedImage out = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            applyHints(g);
            g.setColor(PAGE_BG);
            g.fillRect(0, 0, pageW, pageH);
            int y = PAGE_SIDE_PAD;
            if (includeHeader) {
                drawHeader(g, layout.header(), PAGE_SIDE_PAD, y, contentW);
                y += layout.header().height() + SECTION_GAP;
            }
            for (PageBlock block : blocks) {
                if (block.mainImage() != null) {
                    g.drawImage(block.mainImage(), (pageW - block.mainImage().getWidth()) / 2, y, null);
                } else {
                    drawCard(g, block.card(), PAGE_SIDE_PAD, y, contentW);
                }
                y += block.height() + SECTION_GAP;
            }
        } finally {
            g.dispose();
        }
        return addWatermark(out);
    }

    private static List<CardLayout> measureContentCards(
            ActivityRenderingPipeline.RenderingResult result, int contentW) {
        List<CardLayout> visualCards = measureVisualPages(result.visualPages(), contentW);
        Set<String> seenText = collectTextSignatures(result.visualPages(), result.defineSprites());
        List<CardLayout> measuredCards = new ArrayList<>();
        measuredCards.addAll(measureCards(result.defineSprites(), contentW));
        measuredCards.addAll(measureHoverPages(result.hoverMedium(), result.hoverLarge(), seenText, contentW));

        List<CardLayout> cards = new ArrayList<>();
        cards.addAll(visualCards.stream()
                .filter(card -> isSubPageCard(card) && !isActivityDetailCard(card))
                .toList());
        cards.addAll(measuredCards.stream()
                .filter(card -> isSubPageCard(card) && !isActivityDetailCard(card))
                .toList());
        List<CardLayout> normalTextCards = new ArrayList<>();
        normalTextCards.addAll(visualCards.stream()
                .filter(card -> !isSubPageCard(card) && !isActivityDetailCard(card))
                .toList());
        normalTextCards.addAll(measuredCards.stream()
                .filter(card -> !isSubPageCard(card) && !isActivityDetailCard(card))
                .toList());
        cards.addAll(dedupeTextCards(normalTextCards));

        List<CardLayout> detailCards = new ArrayList<>();
        detailCards.addAll(visualCards.stream()
                .filter(ActivityReportBuilder::isActivityDetailCard)
                .toList());
        detailCards.addAll(measuredCards.stream()
                .filter(ActivityReportBuilder::isActivityDetailCard)
                .toList());
        cards.addAll(dedupeTextCards(detailCards));
        return cards;
    }

    private static HeaderLayout measureHeader(
            ActivityRenderingPipeline.RenderingResult result,
            String originalFileName,
            boolean showOriginalFileName,
            int contentW) {
        BufferedImage scratch = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyHints(g);
            int textW = contentW - CARD_PAD_X * 2;
            List<String> sourceFileLines = List.of();
            int sourceH = 0;
            if (showOriginalFileName && originalFileName != null && !originalFileName.isBlank()) {
                g.setFont(HEADER_SOURCE_FONT);
                FontMetrics sourceFm = g.getFontMetrics();
                sourceFileLines = TooltipComposer.wrapText("原始文件：" + originalFileName, sourceFm, textW);
                sourceH = sourceFileLines.size() * (sourceFm.getHeight() + 2);
            }

            g.setFont(HEADER_TITLE_FONT);
            FontMetrics titleFm = g.getFontMetrics();
            List<String> titleLines = TooltipComposer.wrapText(activityTitle(result), titleFm, textW);

            g.setFont(HEADER_BODY_FONT);
            FontMetrics bodyFm = g.getFontMetrics();
            List<String> bodyLines = new ArrayList<>();
            String description = activityDescription(result);
            if (!description.isBlank()) {
                bodyLines.addAll(TooltipComposer.wrapText(description, bodyFm, textW));
            }
            String timeRange = activityTimeRange(result);
            if (!timeRange.isBlank()) {
                bodyLines.add("活动时间：" + timeRange);
            }
            int titleH = Math.max(titleFm.getHeight(), titleLines.size() * (titleFm.getHeight() + 2));
            int bodyH = bodyLines.isEmpty() ? 0 : bodyLines.size() * (bodyFm.getHeight() + 2);
            int gap = bodyLines.isEmpty() ? 0 : 8;
            int sourceGap = sourceFileLines.isEmpty() ? 0 : 8;
            int height = CARD_PAD_Y * 2 + sourceH + sourceGap + titleH + gap + bodyH;
            return new HeaderLayout(sourceFileLines, titleLines, bodyLines, Math.max(86, height));
        } finally {
            g.dispose();
        }
    }

    private static String activityTitle(ActivityRenderingPipeline.RenderingResult result) {
        if (result.metadata() == null) {
            return "活动预览";
        }
        return cleanHeaderText(result.metadata().title());
    }

    private static String activityDescription(ActivityRenderingPipeline.RenderingResult result) {
        if (result.metadata() == null) {
            return "";
        }
        String title = cleanHeaderText(result.metadata().title());
        String description = cleanHeaderText(result.metadata().description());
        return sameHeaderText(title, description) ? "" : description;
    }

    private static String activityTimeRange(ActivityRenderingPipeline.RenderingResult result) {
        if (result.metadata() == null) {
            return "";
        }
        String start = cleanHeaderText(result.metadata().startTime());
        String end = cleanHeaderText(result.metadata().endTime());
        if (start.isBlank() && end.isBlank()) {
            return "";
        }
        if (start.isBlank()) {
            return "截至 " + end;
        }
        if (end.isBlank()) {
            return start + " 起";
        }
        return start + " 至 " + end;
    }

    private static String cleanHeaderText(String text) {
        return text == null
                ? ""
                : TooltipComposer.cleanText(text).replaceAll("\\s+", " ").strip();
    }

    private static boolean sameHeaderText(String left, String right) {
        return textSignature(left).equals(textSignature(right));
    }

    private static void drawHeader(Graphics2D g, HeaderLayout header, int x, int y, int w) {
        g.setColor(CARD_BG);
        g.fill(new RoundRectangle2D.Double(x, y, w, header.height(), CARD_RADIUS, CARD_RADIUS));
        g.setColor(CARD_BORDER);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, header.height() - 1, CARD_RADIUS, CARD_RADIUS));

        int cursorY = y + CARD_PAD_Y;
        if (!header.sourceFileLines().isEmpty()) {
            g.setFont(HEADER_SOURCE_FONT);
            g.setColor(MUTED_TEXT);
            FontMetrics sourceFm = g.getFontMetrics();
            for (String line : header.sourceFileLines()) {
                cursorY += sourceFm.getAscent();
                g.drawString(line, x + CARD_PAD_X, cursorY);
                cursorY += sourceFm.getDescent() + 2;
            }
            cursorY += 6;
        }

        g.setFont(HEADER_TITLE_FONT);
        g.setColor(CARD_TEXT);
        FontMetrics titleFm = g.getFontMetrics();
        for (String line : header.titleLines()) {
            cursorY += titleFm.getAscent();
            g.drawString(line, x + CARD_PAD_X, cursorY);
            cursorY += titleFm.getDescent() + 2;
        }

        if (!header.bodyLines().isEmpty()) {
            cursorY += 6;
            g.setFont(HEADER_BODY_FONT);
            g.setColor(MUTED_TEXT);
            for (String line : header.bodyLines()) {
                Font lineFont = fitHeaderBodyFont(g, line, w - CARD_PAD_X * 2);
                g.setFont(lineFont);
                FontMetrics bodyFm = g.getFontMetrics();
                cursorY += bodyFm.getAscent();
                g.drawString(line, x + CARD_PAD_X, cursorY);
                cursorY += bodyFm.getDescent() + 2;
            }
        }
    }

    private static Font fitHeaderBodyFont(Graphics2D g, String line, int maxWidth) {
        Font font = HEADER_BODY_FONT;
        g.setFont(font);
        while (g.getFontMetrics().stringWidth(line) > maxWidth && font.getSize() > 12) {
            font = font.deriveFont((float) (font.getSize() - 1));
            g.setFont(font);
        }
        return font;
    }

    private static List<CardLayout> measureVisualPages(List<SwfDecoderFacade.VisualPageContent> pages, int contentW) {
        List<CardLayout> cards = new ArrayList<>();
        BufferedImage scratch = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyHints(g);
            g.setFont(CARD_FONT);
            FontMetrics fm = g.getFontMetrics();
            int textW = contentW - CARD_PAD_X * 2;
            for (SwfDecoderFacade.VisualPageContent page : pages) {
                BufferedImage image = cropVisible(page.image());
                double scale = Math.min(1.0, (contentW - CARD_PAD_X * 2) / (double) Math.max(1, image.getWidth()));
                int imageH = Math.max(1, (int) Math.round(image.getHeight() * scale));
                List<SwfDecoderFacade.TextBlock> normalBlocks = filterTextBlocks(page.textBlocks(), false);
                List<SwfDecoderFacade.TextBlock> detailBlocks = filterTextBlocks(page.textBlocks(), true);
                List<TextBoxLayout> textBoxes = measureTextBoxes(normalBlocks, "", fm, textW);
                int textH = measureTextBoxesHeight(textBoxes);
                if (!normalBlocks.isEmpty() || detailBlocks.isEmpty()) {
                    cards.add(new CardLayout(null, List.of(), image, imageH + CARD_PAD_Y * 2, false));
                }
                if (textH > 0) {
                    cards.add(new CardLayout(null, textBoxes, null, textH + CARD_PAD_Y * 2, false));
                }
                List<TextBoxLayout> detailTextBoxes = measureMergedTextBox(detailBlocks, "", fm, textW);
                int detailTextH = measureTextBoxesHeight(detailTextBoxes);
                if (detailTextH > 0) {
                    cards.add(new CardLayout(null, detailTextBoxes, null, detailTextH + CARD_PAD_Y * 2, true));
                }
            }
        } finally {
            g.dispose();
        }
        return cards;
    }

    private static List<CardLayout> measureCards(List<SwfDecoderFacade.DefineSpriteContent> sprites, int contentW) {
        List<CardLayout> cards = new ArrayList<>();
        BufferedImage scratch = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyHints(g);
            g.setFont(CARD_FONT);
            FontMetrics fm = g.getFontMetrics();
            int textW = contentW - CARD_PAD_X * 2;
            for (SwfDecoderFacade.DefineSpriteContent sprite : sprites) {
                List<SwfDecoderFacade.TextBlock> normalBlocks = filterTextBlocks(sprite.textBlocks(), false);
                List<SwfDecoderFacade.TextBlock> detailBlocks = filterTextBlocks(sprite.textBlocks(), true);
                boolean hasDetailText = !detailBlocks.isEmpty();
                if (hasDetailText && !normalBlocks.isEmpty()) {
                    List<TextBoxLayout> combinedTextBoxes =
                            measureActivityDetailTextBox(detailBlocks, normalBlocks, g, textW);
                    int combinedTextH = measureTextBoxesHeight(combinedTextBoxes);
                    if (combinedTextH > 0) {
                        cards.add(
                                new CardLayout(sprite, combinedTextBoxes, null, combinedTextH + CARD_PAD_Y * 2, true));
                    }
                    continue;
                }
                String normalFallback = sprite.textBlocks().isEmpty() ? sprite.text() : "";
                boolean preformattedColumns = !hasDetailText && isPreformattedColumnText(sprite.text());
                List<TextBoxLayout> textBoxes = preformattedColumns
                        ? measurePreformattedTextBox(sprite.text(), g, textW)
                        : measureMergedTextBox(normalBlocks, normalFallback, fm, textW);
                int textH = measureTextBoxesHeight(textBoxes);
                boolean activityDetail = sprite.activityDetail() || hasDetailText;
                if (sprite.preferImage() && sprite.image() != null && !activityDetail) {
                    BufferedImage cropped = cropVisible(sprite.image());
                    double scale =
                            Math.min(1.0, (contentW - CARD_PAD_X * 2) / (double) Math.max(1, cropped.getWidth()));
                    int imageH = Math.max(1, (int) Math.round(cropped.getHeight() * scale));
                    cards.add(new CardLayout(sprite, List.of(), cropped, imageH + CARD_PAD_Y * 2, activityDetail));
                    if (textH > 0) {
                        cards.add(new CardLayout(sprite, textBoxes, null, textH + CARD_PAD_Y * 2, activityDetail));
                    }
                } else if (!textBoxes.isEmpty()) {
                    cards.add(new CardLayout(sprite, textBoxes, null, textH + CARD_PAD_Y * 2, activityDetail));
                } else if (sprite.image() != null && activityDetail && !hasDetailText) {
                    BufferedImage cropped = cropVisible(sprite.image());
                    double scale =
                            Math.min(1.0, (contentW - CARD_PAD_X * 2) / (double) Math.max(1, cropped.getWidth()));
                    int imageH = Math.max(1, (int) Math.round(cropped.getHeight() * scale));
                    cards.add(new CardLayout(sprite, List.of(), cropped, imageH + CARD_PAD_Y * 2, true));
                } else if (sprite.image() != null && !hasDetailText && !activityDetail) {
                    BufferedImage cropped = cropVisible(sprite.image());
                    double scale =
                            Math.min(1.0, (contentW - CARD_PAD_X * 2) / (double) Math.max(1, cropped.getWidth()));
                    int imageH = Math.max(1, (int) Math.round(cropped.getHeight() * scale));
                    cards.add(new CardLayout(sprite, List.of(), cropped, imageH + CARD_PAD_Y * 2, activityDetail));
                }
                List<TextBoxLayout> detailTextBoxes = measureMergedTextBox(detailBlocks, "", fm, textW);
                int detailTextH = measureTextBoxesHeight(detailTextBoxes);
                if (detailTextH > 0) {
                    cards.add(new CardLayout(sprite, detailTextBoxes, null, detailTextH + CARD_PAD_Y * 2, true));
                }
            }
        } finally {
            g.dispose();
        }
        return cards;
    }

    private static List<CardLayout> measureHoverPages(
            List<ActivityRenderingPipeline.RenderedHoverPage> medium,
            List<ActivityRenderingPipeline.RenderedHoverPage> large,
            Set<String> seenText,
            int contentW) {
        List<CardLayout> cards = new ArrayList<>();
        BufferedImage scratch = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            applyHints(g);
            g.setFont(CARD_FONT);
            FontMetrics fm = g.getFontMetrics();
            int textW = contentW - CARD_PAD_X * 2;
            for (ActivityRenderingPipeline.RenderedHoverPage hover : concatHoverPages(medium, large)) {
                String signature = textSignature(hover.text());
                if (signature.isBlank() || isIgnoredText(hover.text()) || !seenText.add(signature)) {
                    continue;
                }
                List<TextBoxLayout> textBoxes = measureTextBoxes(List.of(), hover.text(), fm, textW);
                int textH = measureTextBoxesHeight(textBoxes);
                if (textH > 0) {
                    cards.add(new CardLayout(null, textBoxes, null, textH + CARD_PAD_Y * 2, false));
                }
            }
        } finally {
            g.dispose();
        }
        return cards;
    }

    private static List<ActivityRenderingPipeline.RenderedHoverPage> concatHoverPages(
            List<ActivityRenderingPipeline.RenderedHoverPage> medium,
            List<ActivityRenderingPipeline.RenderedHoverPage> large) {
        List<ActivityRenderingPipeline.RenderedHoverPage> out = new ArrayList<>();
        out.addAll(medium == null ? List.of() : medium);
        out.addAll(large == null ? List.of() : large);
        return out;
    }

    private static Set<String> collectTextSignatures(
            List<SwfDecoderFacade.VisualPageContent> visualPages, List<SwfDecoderFacade.DefineSpriteContent> sprites) {
        Set<String> seen = new HashSet<>();
        for (SwfDecoderFacade.VisualPageContent page :
                visualPages == null ? List.<SwfDecoderFacade.VisualPageContent>of() : visualPages) {
            addTextSignature(seen, page.text());
            addTextBlockSignatures(seen, page.textBlocks());
        }
        for (SwfDecoderFacade.DefineSpriteContent sprite :
                sprites == null ? List.<SwfDecoderFacade.DefineSpriteContent>of() : sprites) {
            addTextSignature(seen, sprite.text());
            addTextBlockSignatures(seen, sprite.textBlocks());
        }
        return seen;
    }

    private static void addTextBlockSignatures(Set<String> seen, List<SwfDecoderFacade.TextBlock> blocks) {
        for (SwfDecoderFacade.TextBlock block : blocks == null ? List.<SwfDecoderFacade.TextBlock>of() : blocks) {
            addTextSignature(seen, block.text());
        }
    }

    private static void addTextSignature(Set<String> seen, String text) {
        String signature = textSignature(text);
        if (!signature.isBlank()) {
            seen.add(signature);
        }
    }

    private static String textSignature(String text) {
        return TooltipComposer.cleanText(text).replaceAll("\\s+", "");
    }

    private static boolean isSubPageCard(CardLayout card) {
        return card.image() != null;
    }

    private static boolean isActivityDetailCard(CardLayout card) {
        if (card.activityDetail()) {
            return true;
        }
        String text = cardText(card);
        return text.contains("活动细则")
                || text.contains("活动规则")
                || text.contains("注意事项")
                || text.contains("规则说明")
                || text.contains("温馨提示");
    }

    private static List<CardLayout> dedupeTextCards(List<CardLayout> cards) {
        List<CardLayout> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CardLayout card : cards) {
            String signature = textSignature(cardText(card));
            if (signature.isBlank() || seen.add(signature)) {
                out.add(card);
            }
        }
        return out;
    }

    private static List<SwfDecoderFacade.TextBlock> filterTextBlocks(
            List<SwfDecoderFacade.TextBlock> blocks, boolean activityDetail) {
        List<SwfDecoderFacade.TextBlock> source = blocks == null ? List.of() : blocks;
        return source.stream()
                .filter(block -> block.activityDetail() == activityDetail)
                .filter(block -> !isIgnoredText(block.text()))
                .toList();
    }

    private static String cardText(CardLayout card) {
        StringBuilder sb = new StringBuilder();
        for (TextBoxLayout textBox : card.textBoxes()) {
            for (String line : textBox.lines()) {
                sb.append(line).append('\n');
            }
            for (PreformattedLine line : textBox.preformattedLines()) {
                sb.append(line.left()).append('\n');
                if (line.hasRight()) {
                    sb.append(line.right()).append('\n');
                }
            }
            for (List<String> item : textBox.itemLines()) {
                for (String line : item) {
                    sb.append(line).append('\n');
                }
            }
            for (String line : textBox.footerLines()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void drawCard(Graphics2D g, CardLayout card, int x, int y, int w) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Float(x + 3, y + 4, w, card.height(), CARD_RADIUS, CARD_RADIUS));
        g.setColor(CARD_BG);
        g.fill(new RoundRectangle2D.Float(x, y, w, card.height(), CARD_RADIUS, CARD_RADIUS));
        g.setColor(CARD_BORDER);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Float(x, y, w, card.height(), CARD_RADIUS, CARD_RADIUS));

        int contentY = y + CARD_PAD_Y;
        if (card.image() != null) {
            int maxW = w - CARD_PAD_X * 2;
            double scale =
                    Math.min(1.0, maxW / (double) Math.max(1, card.image().getWidth()));
            int drawW = Math.max(1, (int) Math.round(card.image().getWidth() * scale));
            int drawH = Math.max(1, (int) Math.round(card.image().getHeight() * scale));
            int imageX = x + CARD_PAD_X + Math.max(0, (maxW - drawW) / 2);
            g.drawImage(card.image(), imageX, contentY, drawW, drawH, null);
            contentY += drawH + (card.textBoxes().isEmpty() ? 0 : CARD_GAP);
        }

        g.setFont(CARD_FONT);
        g.setColor(CARD_TEXT);
        for (TextBoxLayout textBox : card.textBoxes()) {
            drawTextBox(g, textBox, x + CARD_PAD_X, contentY, w - CARD_PAD_X * 2);
            contentY += textBox.height() + CARD_GAP;
        }
    }

    private static List<TextBoxLayout> measureTextBoxes(
            List<SwfDecoderFacade.TextBlock> blocks, String fallbackText, FontMetrics fm, int maxWidth) {
        List<SwfDecoderFacade.TextBlock> source = blocks == null ? List.of() : blocks;
        if (source.isEmpty() && fallbackText != null && !fallbackText.isBlank()) {
            source = List.of(new SwfDecoderFacade.TextBlock(0, 0, 0, fallbackText));
        }
        List<TextBoxLayout> out = new ArrayList<>();
        int lineH = fm.getHeight() + 4;
        int textW = Math.max(80, maxWidth - CARD_PAD_X * 2);
        for (SwfDecoderFacade.TextBlock block : source) {
            TextBoxContent content = measureTextBoxContent(block.text(), fm, textW, lineH);
            if (content.lines().isEmpty()
                    && content.itemLines().isEmpty()
                    && content.footerLines().isEmpty()) {
                continue;
            }
            out.add(new TextBoxLayout(
                    content.lines(), content.itemLines(), content.footerLines(), content.height() + CARD_PAD_Y * 2));
        }
        return out;
    }

    private static List<TextBoxLayout> measureMergedTextBox(
            List<SwfDecoderFacade.TextBlock> blocks, String fallbackText, FontMetrics fm, int maxWidth) {
        String text = mergeTextBlocks(blocks, fallbackText);
        if (text.isBlank()) {
            return List.of();
        }
        int lineH = fm.getHeight() + 4;
        int textW = Math.max(80, maxWidth - CARD_PAD_X * 2);
        TextBoxContent content = measureTextBoxContent(text, fm, textW, lineH);
        if (content.lines().isEmpty()
                && content.itemLines().isEmpty()
                && content.footerLines().isEmpty()) {
            return List.of();
        }
        return List.of(new TextBoxLayout(
                content.lines(), content.itemLines(), content.footerLines(), content.height() + CARD_PAD_Y * 2));
    }

    private static List<TextBoxLayout> measureActivityDetailTextBox(
            List<SwfDecoderFacade.TextBlock> detailBlocks,
            List<SwfDecoderFacade.TextBlock> normalBlocks,
            Graphics2D g,
            int maxWidth) {
        String detailText = mergeTextBlocks(detailBlocks, "");
        if (detailText.isBlank()) {
            return List.of();
        }
        FontMetrics fm = g.getFontMetrics(ACTIVITY_DETAIL_FONT);
        int lineH = fm.getHeight() + 4;
        int textW = Math.max(80, maxWidth - CARD_PAD_X * 2);
        List<String> lines = wrapExplicitLines(detailText, fm, textW);
        List<PreformattedLine> columnRows = pairedColumnRows(normalBlocks);
        int height = lines.isEmpty() ? 0 : lines.size() * lineH - 4;
        if (!columnRows.isEmpty()) {
            if (height > 0) {
                height += CARD_GAP;
            }
            height += columnRows.size() * lineH - 4;
        }
        if (height <= 0) {
            return List.of();
        }
        return List.of(
                new TextBoxLayout(lines, List.of(), List.of(), height + CARD_PAD_Y * 2, false, columnRows, true));
    }

    private static List<TextBoxLayout> measurePreformattedTextBox(String text, Graphics2D g, int maxWidth) {
        List<PreformattedLine> lines = preformattedLines(text);
        if (lines.isEmpty()) {
            return List.of();
        }
        int textW = Math.max(80, maxWidth - CARD_PAD_X * 2);
        Font font = fitPreformattedFont(g, lines, textW);
        FontMetrics fm = g.getFontMetrics(font);
        int lineH = fm.getHeight() + 3;
        int height = lines.size() * lineH - 3 + CARD_PAD_Y * 2;
        return List.of(new TextBoxLayout(List.of(), List.of(), List.of(), height, true, lines, false));
    }

    private static boolean isPreformattedColumnText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int columnRows = 0;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.matches(".*\\S {4,}\\S.*")) {
                columnRows++;
            }
        }
        return columnRows >= 2;
    }

    private static List<PreformattedLine> preformattedLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<PreformattedLine> lines = new ArrayList<>();
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.stripLeading().stripTrailing();
            if (!line.isBlank() && !isIgnoredText(line)) {
                Matcher columnMatcher = Pattern.compile("^(.*?) {4,}(.*)$").matcher(line);
                if (columnMatcher.matches()) {
                    lines.add(new PreformattedLine(
                            columnMatcher.group(1).stripTrailing(),
                            columnMatcher.group(2).stripLeading()));
                } else {
                    lines.add(new PreformattedLine(line, ""));
                }
            }
        }
        return lines;
    }

    private static Font fitPreformattedFont(Graphics2D g, List<PreformattedLine> lines, int maxWidth) {
        Font font = PREFORMATTED_FONT;
        while (font.getSize() > 11 && maxPreformattedLineWidth(g, font, lines, maxWidth) > maxWidth) {
            font = font.deriveFont((float) (font.getSize() - 1));
        }
        return font;
    }

    private static int maxPreformattedLineWidth(Graphics2D g, Font font, List<PreformattedLine> lines, int maxWidth) {
        FontMetrics fm = g.getFontMetrics(font);
        int columnX = preformattedColumnX(fm, lines, maxWidth);
        int max = 0;
        for (PreformattedLine line : lines) {
            int width = line.hasRight() ? columnX + fm.stringWidth(line.right()) : fm.stringWidth(line.left());
            max = Math.max(max, width);
        }
        return max;
    }

    private static int preformattedColumnX(FontMetrics fm, List<PreformattedLine> lines, int maxWidth) {
        int maxLeft = 0;
        for (PreformattedLine line : lines) {
            if (line.hasRight()) {
                maxLeft = Math.max(maxLeft, fm.stringWidth(line.left()));
            }
        }
        int target = (int) Math.round(maxWidth * PREFORMATTED_COLUMN_RATIO);
        return Math.max(target, maxLeft + PREFORMATTED_COLUMN_GAP);
    }

    private static List<PreformattedLine> pairedColumnRows(List<SwfDecoderFacade.TextBlock> blocks) {
        if (blocks == null || blocks.size() != 2) {
            return List.of();
        }
        List<SwfDecoderFacade.TextBlock> sorted =
                blocks.stream().sorted((a, b) -> Integer.compare(a.x(), b.x())).toList();
        List<String> left = splitNonBlankLines(sorted.get(0).text());
        List<String> right = splitNonBlankLines(sorted.get(1).text());
        if (left.size() < 2 || left.size() != right.size()) {
            return List.of();
        }
        List<PreformattedLine> rows = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            rows.add(new PreformattedLine(left.get(i), right.get(i)));
        }
        return rows;
    }

    private static String mergeTextBlocks(List<SwfDecoderFacade.TextBlock> blocks, String fallbackText) {
        List<String> parts = new ArrayList<>();
        for (SwfDecoderFacade.TextBlock block : blocks == null ? List.<SwfDecoderFacade.TextBlock>of() : blocks) {
            String text = block.text() == null ? "" : block.text().trim();
            if (!text.isBlank() && !isIgnoredText(text)) {
                parts.add(text);
            }
        }
        if (parts.isEmpty() && fallbackText != null && !fallbackText.isBlank() && !isIgnoredText(fallbackText)) {
            parts.add(fallbackText.trim());
        }
        return String.join("\n", parts);
    }

    private static boolean isIgnoredText(String text) {
        String signature = textSignature(text);
        return signature.startsWith("适用于");
    }

    private static TextBoxContent measureTextBoxContent(String text, FontMetrics fm, int textW, int lineH) {
        if (isNumberedActivityRuleText(text)) {
            List<String> lines = wrapExplicitLines(text, fm, textW);
            int height = lines.isEmpty() ? 0 : lines.size() * lineH - 4;
            return new TextBoxContent(lines, List.of(), List.of(), height);
        }
        RewardGridText gridText = parseRewardGridText(text);
        List<String> lines = new ArrayList<>();
        List<List<String>> itemLines = new ArrayList<>();
        if (gridText.items().size() >= ITEM_GRID_MIN_COUNT) {
            for (String header : gridText.headers()) {
                lines.addAll(TooltipComposer.wrapText(header, fm, textW));
            }
            int cellW = itemCellWidth(textW);
            int cellTextW = Math.max(40, cellW - ITEM_CELL_PAD_X * 2);
            for (String item : gridText.items()) {
                List<String> wrapped = TooltipComposer.wrapText(item, fm, cellTextW);
                itemLines.add(wrapped.isEmpty() ? List.of(item) : wrapped);
            }
        } else {
            lines.addAll(TooltipComposer.wrapText(text, fm, textW));
        }
        List<String> footerLines = new ArrayList<>();
        if (!itemLines.isEmpty()) {
            for (String footer : gridText.footers()) {
                footerLines.addAll(TooltipComposer.wrapText(footer, fm, textW));
            }
        }

        int height = 0;
        if (!lines.isEmpty()) {
            height += lines.size() * lineH - 4;
        }
        if (!itemLines.isEmpty()) {
            if (height > 0) {
                height += CARD_GAP;
            }
            height += measureItemGridHeight(itemLines, lineH);
        }
        if (!footerLines.isEmpty()) {
            if (height > 0) {
                height += CARD_GAP;
            }
            height += footerLines.size() * lineH - 4;
        }
        return new TextBoxContent(lines, itemLines, footerLines, height);
    }

    private static boolean isNumberedActivityRuleText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.contains("活动规则")
                || normalized.contains("活动细则")
                || normalized.contains("规则说明")
                || normalized.contains("注意事项")) {
            return true;
        }
        int numberedLines = 0;
        for (String line : normalized.split("\n")) {
            if (line.stripLeading().matches("^\\d+[.．].+")) {
                numberedLines++;
            }
        }
        return numberedLines >= 2;
    }

    private static List<String> wrapExplicitLines(String text, FontMetrics fm, int textW) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            if (!line.isBlank() && !isIgnoredText(line)) {
                lines.addAll(TooltipComposer.wrapText(line, fm, textW));
            }
        }
        return lines;
    }

    private static List<String> splitNonBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            if (!line.isBlank() && !isIgnoredText(line)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static RewardGridText parseRewardGridText(String text) {
        if (text == null || text.isBlank()) {
            return new RewardGridText(List.of(), List.of(), List.of());
        }
        List<String> headers = new ArrayList<>();
        List<String> items = new ArrayList<>();
        List<String> footers = new ArrayList<>();
        boolean seenItemLine = false;
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            Matcher matcher = REWARD_ITEM_PATTERN.matcher(line);
            int cursor = 0;
            StringBuilder header = new StringBuilder();
            while (matcher.find()) {
                header.append(line, cursor, matcher.start());
                String item = cleanRewardItem(matcher.group(1));
                if (!item.isBlank()) {
                    items.add(item);
                }
                cursor = matcher.end();
            }
            boolean hasItems = cursor > 0;
            header.append(line.substring(cursor));
            String cleanedHeader = cleanRewardHeader(header.toString());
            if (!cleanedHeader.isBlank()) {
                if (seenItemLine && !hasItems) {
                    footers.add(cleanedHeader);
                } else {
                    headers.add(cleanedHeader);
                }
            } else if (!hasItems) {
                if (seenItemLine) {
                    footers.add(line);
                } else {
                    headers.add(line);
                }
            }
            if (hasItems) {
                seenItemLine = true;
            }
        }
        return new RewardGridText(headers, items, footers);
    }

    private static String cleanRewardItem(String item) {
        return item == null ? "" : item.replaceFirst("^[+，,、；;]+", "").trim();
    }

    private static String cleanRewardHeader(String header) {
        return header == null ? "" : header.replaceAll("[+，,、；;\\s]+$", "").trim();
    }

    private static int itemCellWidth(int w) {
        return Math.max(1, (w - ITEM_CELL_GAP * (ITEM_GRID_COLUMNS - 1)) / ITEM_GRID_COLUMNS);
    }

    private static int measureItemGridHeight(List<List<String>> itemLines, int lineH) {
        int height = 0;
        for (int i = 0; i < itemLines.size(); i += ITEM_GRID_COLUMNS) {
            int rowItems = Math.min(ITEM_GRID_COLUMNS, itemLines.size() - i);
            int maxLines = 1;
            for (int j = 0; j < rowItems; j++) {
                maxLines = Math.max(maxLines, Math.max(1, itemLines.get(i + j).size()));
            }
            height += maxLines * lineH - 4 + ITEM_CELL_PAD_Y * 2;
            if (i + ITEM_GRID_COLUMNS < itemLines.size()) {
                height += ITEM_CELL_GAP;
            }
        }
        return height;
    }

    private static int measureTextBoxesHeight(List<TextBoxLayout> textBoxes) {
        if (textBoxes.isEmpty()) {
            return 0;
        }
        int height = 0;
        for (TextBoxLayout textBox : textBoxes) {
            height += textBox.height() + CARD_GAP;
        }
        return height - CARD_GAP;
    }

    private static void drawTextBox(Graphics2D g, TextBoxLayout textBox, int x, int y, int w) {
        g.setColor(TEXT_BOX_BG);
        g.fill(new RoundRectangle2D.Float(x, y, w, textBox.height(), CARD_RADIUS, CARD_RADIUS));
        g.setColor(TEXT_BOX_BORDER);
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new RoundRectangle2D.Float(x, y, w, textBox.height(), CARD_RADIUS, CARD_RADIUS));

        if (textBox.preformatted()) {
            drawPreformattedTextBox(g, textBox, x, y, w);
            return;
        }

        g.setFont(textBox.compactText() ? ACTIVITY_DETAIL_FONT : CARD_FONT);
        g.setColor(CARD_TEXT);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight() + 4;
        int textY = y + CARD_PAD_Y + fm.getAscent();
        for (String line : textBox.lines()) {
            g.drawString(line, x + CARD_PAD_X, textY);
            textY += lineH;
        }
        int gridY = textY - fm.getAscent();
        if (!textBox.preformattedLines().isEmpty()) {
            if (!textBox.lines().isEmpty()) {
                gridY += CARD_GAP;
            }
            drawColumnLines(g, textBox.preformattedLines(), x + CARD_PAD_X, gridY, w - CARD_PAD_X * 2, lineH);
            gridY += textBox.preformattedLines().size() * lineH - 4;
        }
        if (!textBox.lines().isEmpty() && !textBox.itemLines().isEmpty()) {
            gridY += CARD_GAP;
        }
        if (!textBox.itemLines().isEmpty()) {
            drawItemGrid(g, textBox.itemLines(), x + CARD_PAD_X, gridY, w - CARD_PAD_X * 2, lineH);
            gridY += measureItemGridHeight(textBox.itemLines(), lineH);
        }
        if (!textBox.footerLines().isEmpty()) {
            int footerY = gridY + CARD_GAP + fm.getAscent();
            for (String line : textBox.footerLines()) {
                g.drawString(line, x + CARD_PAD_X, footerY);
                footerY += lineH;
            }
        }
    }

    private static void drawPreformattedTextBox(Graphics2D g, TextBoxLayout textBox, int x, int y, int w) {
        int textW = w - CARD_PAD_X * 2;
        g.setFont(fitPreformattedFont(g, textBox.preformattedLines(), textW));
        g.setColor(CARD_TEXT);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight() + 3;
        int leftX = x + CARD_PAD_X;
        int rightX = leftX + preformattedColumnX(fm, textBox.preformattedLines(), textW);
        int textY = y + CARD_PAD_Y + fm.getAscent();
        for (PreformattedLine line : textBox.preformattedLines()) {
            g.drawString(line.left(), leftX, textY);
            if (line.hasRight()) {
                g.drawString(line.right(), rightX, textY);
            }
            textY += lineH;
        }
    }

    private static void drawColumnLines(Graphics2D g, List<PreformattedLine> lines, int x, int y, int w, int lineH) {
        if (lines.isEmpty()) {
            return;
        }
        FontMetrics fm = g.getFontMetrics();
        int rightX = x + compactColumnX(fm, lines, w);
        int textY = y + fm.getAscent();
        for (PreformattedLine line : lines) {
            g.drawString(line.left(), x, textY);
            if (line.hasRight()) {
                g.drawString(line.right(), rightX, textY);
            }
            textY += lineH;
        }
    }

    private static int compactColumnX(FontMetrics fm, List<PreformattedLine> lines, int maxWidth) {
        int maxLeft = 0;
        for (PreformattedLine line : lines) {
            if (line.hasRight()) {
                maxLeft = Math.max(maxLeft, fm.stringWidth(line.left()));
            }
        }
        return Math.min(Math.max(maxLeft + PREFORMATTED_COLUMN_GAP, maxWidth / 5), maxWidth / 2);
    }

    private static void drawItemGrid(Graphics2D g, List<List<String>> itemLines, int x, int y, int w, int lineH) {
        int cellW = itemCellWidth(w);
        int cursorY = y;
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < itemLines.size(); i += ITEM_GRID_COLUMNS) {
            int rowItems = Math.min(ITEM_GRID_COLUMNS, itemLines.size() - i);
            int maxLines = 1;
            for (int j = 0; j < rowItems; j++) {
                maxLines = Math.max(maxLines, Math.max(1, itemLines.get(i + j).size()));
            }
            int rowH = maxLines * lineH - 4 + ITEM_CELL_PAD_Y * 2;
            for (int j = 0; j < rowItems; j++) {
                int cellX = x + j * (cellW + ITEM_CELL_GAP);
                g.setColor(CARD_BG);
                g.fill(new RoundRectangle2D.Float(cellX, cursorY, cellW, rowH, CARD_RADIUS, CARD_RADIUS));
                g.setColor(TEXT_BOX_BORDER);
                g.draw(new RoundRectangle2D.Float(cellX, cursorY, cellW, rowH, CARD_RADIUS, CARD_RADIUS));
                g.setColor(CARD_TEXT);
                int itemTextY = cursorY + ITEM_CELL_PAD_Y + fm.getAscent();
                for (String line : itemLines.get(i + j)) {
                    g.drawString(line, cellX + ITEM_CELL_PAD_X, itemTextY);
                    itemTextY += lineH;
                }
            }
            cursorY += rowH + ITEM_CELL_GAP;
        }
    }

    private static void addPage(PdfDocument pdf, BufferedImage image) throws IOException {
        ByteArrayOutputStream pngBuf = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", pngBuf)) {
            throw new IOException("PNG ImageWriter 不可用");
        }
        var page = pdf.addNewPage(new PageSize(image.getWidth(), image.getHeight()));
        var canvas = new PdfCanvas(page);
        canvas.addImageFittedIntoRectangle(
                ImageDataFactory.create(pngBuf.toByteArray()),
                new Rectangle(0, 0, image.getWidth(), image.getHeight()),
                false);
    }

    private static void writePng(BufferedImage img, Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            if (!ImageIO.write(img, "PNG", out)) {
                throw new IOException("PNG ImageWriter 不可用: " + path);
            }
        }
    }

    private static void cleanupGeneratedArtifacts(Path outputDir) throws IOException {
        Files.deleteIfExists(outputDir.resolve("main.png"));
        Files.deleteIfExists(outputDir.resolve("report.png"));
        Files.deleteIfExists(outputDir.resolve("report.pdf"));
        Files.deleteIfExists(outputDir.resolve("define-sprites.json"));
        try (var stream = Files.list(outputDir)) {
            for (Path path : stream.filter(ActivityReportBuilder::isGeneratedDefineSpritePng)
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isGeneratedDefineSpritePng(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("define-sprite-") && name.endsWith(".png");
    }

    private static BufferedImage cropVisible(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                int rgb = image.getRGB(x, y) & 0x00ffffff;
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
            return image;
        }
        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private record CardLayout(
            SwfDecoderFacade.DefineSpriteContent sprite,
            List<TextBoxLayout> textBoxes,
            BufferedImage image,
            int height,
            boolean activityDetail) {}

    private record ReportInput(ActivityRenderingPipeline.RenderingResult result, String originalFileName) {}

    private record PageBlock(BufferedImage mainImage, CardLayout card, int height) {}

    private record ReportLayout(int pageW, int contentW, HeaderLayout header, List<PageBlock> blocks) {}

    private record HeaderLayout(
            List<String> sourceFileLines, List<String> titleLines, List<String> bodyLines, int height) {}

    private record TextBoxLayout(
            List<String> lines,
            List<List<String>> itemLines,
            List<String> footerLines,
            int height,
            boolean preformatted,
            List<PreformattedLine> preformattedLines,
            boolean compactText) {
        private TextBoxLayout(List<String> lines, List<List<String>> itemLines, List<String> footerLines, int height) {
            this(lines, itemLines, footerLines, height, false, List.of(), false);
        }
    }

    private record PreformattedLine(String left, String right) {
        private boolean hasRight() {
            return right != null && !right.isBlank();
        }
    }

    private record TextBoxContent(
            List<String> lines, List<List<String>> itemLines, List<String> footerLines, int height) {}

    private record RewardGridText(List<String> headers, List<String> items, List<String> footers) {}

    public record ReportPaths(Path mainPng, Path pdf, Path reportPng) {

        public ReportPaths(Path mainPng, Path pdf) {
            this(mainPng, pdf, null);
        }
    }

    public record ReportOptions(boolean showOriginalFileName, List<String> originalFileNames) {

        public ReportOptions {
            if (originalFileNames == null) {
                originalFileNames = List.of();
            } else {
                List<String> normalized = new ArrayList<>(originalFileNames.size());
                for (String fileName : originalFileNames) {
                    normalized.add(fileName == null ? "" : fileName);
                }
                originalFileNames = List.copyOf(normalized);
            }
        }

        public ReportOptions(boolean showOriginalFileName) {
            this(showOriginalFileName, List.of());
        }

        public static ReportOptions production() {
            return new ReportOptions(false);
        }
    }
}
