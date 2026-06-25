package com.strikegod.engine.gateway.game.activity;

import com.strikegod.engine.core.crypto.swf.SwfResourceDecryptor;
import com.strikegod.engine.gateway.game.resource.GameResourceHttpDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 把游戏端 res_config + 活动 url → 解密后的 SWF 字节流。
 *
 * <p>由 {@link ActivityDispatcherJob} 与 {@code ActivityFetchService.reparseActivity} 共用。
 *
 * <p>从历史的 {@code ActivityRenderWorkerQueueService} 中抽取出来 —— 现在 Engine 自己在内存里
 * 处理 SWF（不再 7z 打包给 Worker），这一步成为新管线的入口。
 */
@Slf4j
@Service
public class ActivitySwfFetcher {

    private static final Pattern ACT_SWF_PATTERN =
            Pattern.compile("<act([^\\s>/]+)[^>]*url=[\"']([^\"']+\\.swf)[\"'][^>]*/?>");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 基于 activityList.xml 元数据拉取活动 SWF,并解密为可被 内置 FFDEC 打开的 FWS 字节流。
     *
     * @throws IllegalStateException 活动缺 url、res_config 中找不到对应 SWF、下载或解密失败
     */
    public byte[] fetchAndDecrypt(ActivityXmlMetadata metadata, String resConfigXml, String resurlBack)
            throws IOException {
        SwfResource resource = resolveOne(metadata, resConfigXml);
        return downloadAndDecrypt(resource, resurlBack);
    }

    /** 按 activityList.xml 的明确 url 拉取活动 SWF，不再通过去尾号猜测同家族 SWF。 */
    public List<SwfPayload> fetchAndDecryptExact(ActivityXmlMetadata metadata, String resConfigXml, String resurlBack)
            throws IOException {
        return fetchAndDecryptExact(List.of(metadata), resConfigXml, resurlBack);
    }

    /** 按 activityList.xml / 按钮 tid 明确给出的活动链路拉取多个 SWF。 */
    public List<SwfPayload> fetchAndDecryptExact(
            List<ActivityXmlMetadata> metadataList, String resConfigXml, String resurlBack) throws IOException {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("活动报告至少需要一个 activityList.xml 元数据");
        }
        List<SwfPayload> payloads = new java.util.ArrayList<>();
        for (ActivityXmlMetadata metadata : metadataList) {
            payloads.add(fetchOne(metadata, resConfigXml, resurlBack));
        }
        return payloads;
    }

    private SwfPayload fetchOne(ActivityXmlMetadata metadata, String resConfigXml, String resurlBack)
            throws IOException {
        SwfResource resource = resolveOne(metadata, resConfigXml);
        return new SwfPayload(
                resource.metadata(),
                downloadAndDecrypt(resource, resurlBack),
                originalFileName(resource.relativePath()));
    }

    private byte[] downloadAndDecrypt(SwfResource resource, String resurlBack) throws IOException {
        String swfUrl = (resurlBack.endsWith("/") ? resurlBack : resurlBack + "/") + resource.relativePath();
        log.info(
                "[活动 SWF] 下载 activityId={} activityUrl={} url={}",
                resource.metadata().activityId(),
                resource.metadata().url(),
                swfUrl);
        byte[] rawSwf = GameResourceHttpDownloader.downloadBytes(httpClient, swfUrl, Duration.ofSeconds(60));
        return SwfResourceDecryptor.decrypt(rawSwf);
    }

    private SwfResource resolveOne(ActivityXmlMetadata metadata, String resConfigXml) {
        String activityUrl = metadata.url();
        if (activityUrl == null || activityUrl.isBlank()) {
            throw new IllegalStateException("活动 " + metadata.activityId() + " 缺少 activityList.xml url 字段");
        }
        String swfRelativePath = resolveSwfPath(activityUrl, resConfigXml);
        if (swfRelativePath == null || swfRelativePath.isBlank()) {
            throw new IllegalStateException(
                    "res_config 中未找到活动 " + metadata.activityId() + " (url=" + activityUrl + ") 对应的 SWF");
        }
        return new SwfResource(metadata, swfRelativePath);
    }

    /**
     * 在 res_config XML 中按活动 url 标识找到 .swf 文件相对路径。
     *
     * <p>优先匹配 {@code <act&lt;url&gt; url="..." encode="true"/>}，找不到再降级匹配任何
     * {@code <act&lt;url&gt; url="...swf"/>}。
     */
    private static String resolveSwfPath(String activityUrl, String resConfigXml) {
        String escapedUrl = Pattern.quote(activityUrl);
        Pattern pattern =
                Pattern.compile("<act" + escapedUrl + "\\s+url=[\"']([^\"']+)[\"'][^>]*encode=[\"']true[\"'][^>]*/>");
        Matcher matcher = pattern.matcher(resConfigXml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Pattern fallback = Pattern.compile("<act" + escapedUrl + "[^>]*url=[\"']([^\"']+\\.swf)[\"'][^>]*/>");
        Matcher fallbackMatcher = fallback.matcher(resConfigXml);
        return fallbackMatcher.find() ? fallbackMatcher.group(1) : null;
    }

    private static String originalFileName(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
    }

    private record SwfResource(ActivityXmlMetadata metadata, String relativePath) {}

    public record SwfPayload(ActivityXmlMetadata metadata, byte[] bytes, String originalFileName) {

        public SwfPayload(ActivityXmlMetadata metadata, byte[] bytes) {
            this(metadata, bytes, null);
        }
    }
}
