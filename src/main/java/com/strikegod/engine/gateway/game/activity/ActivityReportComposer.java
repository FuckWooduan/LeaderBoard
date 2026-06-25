package com.strikegod.engine.gateway.game.activity;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SwfOpenException;
import com.strikegod.engine.gateway.game.activity.swf.ActivityRenderingPipeline;
import com.strikegod.engine.gateway.game.activity.swf.ActivityReportBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 活动报告编排器:把 SWF bytes 一站式转成「报告目录」(HTML + PDF + 图片资源)。
 *
 * <p>纯 ffdec 路线 ── 主帧 / hover / click 全部走 内置 FFDEC 源码模块;原 Worker (Flash Player + Win32 截图) 子项目已删除,
 * 不再有 RPC 通道。
 *
 * <pre>
 *   pipeline.render (activityList.xml 元数据 + ffdec 主帧 + hover/click 合成) ──┐
 *                                                                                ├── reportBuilder.writeAll → ReportPaths
 *   activityXmlMetadata, outputDir                                            ──┘
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityReportComposer {

    private final ActivityRenderingPipeline pipeline;
    private final ActivityReportBuilder reportBuilder;

    /**
     * 编排单条活动报告。
     *
     * <p>失败策略:
     *
     * <ul>
     *   <li>pipeline 渲染失败 (SWF 解析错):抛 {@link IOException} / {@link SwfOpenException},由 caller 标 activity 失败状态
     *   <li>iText PDF 生成失败:HTML 仍然可用,{@code ReportPaths.pdf()} 为 {@code null}
     * </ul>
     *
     * @param swfBytes 已解密的 SWF 字节流
     * @param metadata activityList.xml 中对应 {@code <child>} 的活动元数据
     * @param outputDir 报告输出目录(自动创建)
     * @return HTML / PDF 路径(PDF 可能为 null)
     */
    public ActivityReportBuilder.ReportPaths compose(byte[] swfBytes, ActivityXmlMetadata metadata, Path outputDir)
            throws IOException, SwfOpenException {
        return compose(swfBytes, metadata, outputDir, false);
    }

    public ActivityReportBuilder.ReportPaths compose(
            byte[] swfBytes, ActivityXmlMetadata metadata, Path outputDir, boolean showOriginalFileName)
            throws IOException, SwfOpenException {
        ActivityRenderingPipeline.RenderingResult result = pipeline.render(swfBytes, metadata);
        log.debug(
                "[活动报告] 渲染完成 activityId={} title={} click={} hoverS={} hoverM={} hoverL={} warnings={}",
                metadata.activityId(),
                metadata.title(),
                result.clickFrames().size(),
                result.hoverSmall().size(),
                result.hoverMedium().size(),
                result.hoverLarge().size(),
                result.warnings().size());
        return reportBuilder.writeAll(result, metadata.title(), outputDir, showOriginalFileName);
    }

    /** 编排已解析出的 SWF payload 列表；生产路径当前只传入 activityList.xml 精确 URL 对应的单个 SWF。 */
    public ActivityReportBuilder.ReportPaths compose(
            List<ActivitySwfFetcher.SwfPayload> swfs, ActivityXmlMetadata metadata, Path outputDir)
            throws IOException, SwfOpenException {
        return compose(swfs, metadata, outputDir, false);
    }

    public ActivityReportBuilder.ReportPaths compose(
            List<ActivitySwfFetcher.SwfPayload> swfs,
            ActivityXmlMetadata metadata,
            Path outputDir,
            boolean showOriginalFileName)
            throws IOException, SwfOpenException {
        if (swfs == null || swfs.isEmpty()) {
            throw new IllegalArgumentException("活动报告至少需要一个 SWF");
        }
        List<ActivityRenderingPipeline.RenderingResult> results = new ArrayList<>(swfs.size());
        for (ActivitySwfFetcher.SwfPayload swf : swfs) {
            ActivityRenderingPipeline.RenderingResult result = pipeline.render(swf.bytes(), swf.metadata());
            log.debug(
                    "[活动报告] 渲染完成 activityId={} swfUrl={} title={} click={} hoverS={} hoverM={} hoverL={} warnings={}",
                    swf.metadata().activityId(),
                    swf.metadata().url(),
                    swf.metadata().title(),
                    result.clickFrames().size(),
                    result.hoverSmall().size(),
                    result.hoverMedium().size(),
                    result.hoverLarge().size(),
                    result.warnings().size());
            results.add(result);
        }
        return reportBuilder.writeAll(
                results,
                metadata.title(),
                outputDir,
                new ActivityReportBuilder.ReportOptions(
                        showOriginalFileName,
                        swfs.stream()
                                .map(ActivitySwfFetcher.SwfPayload::originalFileName)
                                .map(name -> name == null ? "" : name)
                                .toList()));
    }
}
