package com.rankharvester.activity;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.gamedata.GameResourceDecryptor;
import com.rankharvester.net.FlashvarsClient;
import com.rankharvester.net.GameServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 活动抓取（HTTP，移植自 StrikeGod 引擎 ActivityFetchService 的下载链；与 {@link com.rankharvester.gamedata.GameDataFetcher} 同构）。
 *
 * <p>流程：取带 pauth 的可用账号 → flashvars(含 resurl_back/resConfigFileName) → 下载 res_config.xml →
 * 正则取 {@code <activityListXML url version>} → 下载 activityList → 解密为 XML → {@link ActivityListParser} 解析
 * {@code <child>} 节点为活动行。直连游戏 CDN，不走代理。
 */
@Component
public class ActivityFetcher {

    private static final Logger log = LoggerFactory.getLogger(ActivityFetcher.class);

    /** res_config 里活动列表引用：{@code <activityListXML url="..." version="...">}（属性顺序不定）。 */
    private static final Pattern ACT_LIST_TAG = Pattern.compile("<activityListXML\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private final AccountRegistry accounts;
    private final FlashvarsClient flashvars;
    private final ActivityListParser parser;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ActivityFetcher(AccountRegistry accounts, FlashvarsClient flashvars, ActivityListParser parser) {
        this.accounts = accounts;
        this.flashvars = flashvars;
        this.parser = parser;
    }

    /** 用库里账号的 pauth 抓活动。失败抛 {@link IllegalStateException}。 */
    public List<ActivityRow> fetch() {
        var account = accounts.all().stream()
                .filter(a -> a.enabled() && a.available()
                        && a.pauth() != null && !a.pauth().isBlank() && !a.servers().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用账号(带 pauth)"));
        return fetch(account.pauth(), account.servers());
    }

    /** 显式 pauth + 候选区服（依次尝试，取第一个成功的）。 */
    public List<ActivityRow> fetch(String pauth, List<String> servers) {
        return fetchBundle(pauth, servers).rows();
    }

    /**
     * 活动抓取结果（含渲染长图所需的 res 上下文）。
     *
     * @param rows         活动行
     * @param resConfigXml res_config.xml 文本（活动 SWF 在其中按 {@code <act.. url=...swf>} 定位）
     * @param resurlBack   资源 CDN 根（下载活动 SWF 用：resurlBack + "/" + swf路径）
     */
    public record FetchBundle(List<ActivityRow> rows, String resConfigXml, String resurlBack) {}

    /** 用库里账号的 pauth 抓活动（含 res 上下文，供长图渲染）。 */
    public FetchBundle fetchBundle() {
        var account = accounts.all().stream()
                .filter(a -> a.enabled() && a.available()
                        && a.pauth() != null && !a.pauth().isBlank() && !a.servers().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用账号(带 pauth)"));
        return fetchBundle(account.pauth(), account.servers());
    }

    /** 显式 pauth + 候选区服，返回活动行 + res 上下文（resConfigXml / resurlBack）。 */
    public FetchBundle fetchBundle(String pauth, List<String> servers) {
        if (pauth == null || pauth.isBlank()) {
            throw new IllegalStateException("pauth 为空");
        }
        // 活动列表是全局的（flashvars 用 pauth+serverId 取，与账号归属无关）。
        // 优先用 双线一区(1) + 电信一区(2) 抓，再回退到账号自带区服 / 默认区。LinkedHashSet 去重保序。
        var order = new java.util.LinkedHashSet<String>();
        order.add("1"); // 双线一区
        order.add("2"); // 电信一区
        if (servers != null) {
            for (String s : servers) {
                if (s != null && !s.isBlank()) {
                    order.add(s.trim());
                }
            }
        }
        order.add("6");
        order.add("5");
        IllegalStateException last = null;
        for (String server : order) {
            try {
                int serverId = GameServer.fromIdOrThrow(Integer.parseInt(server.trim())).id();
                var fv = flashvars.fetch(pauth, serverId);
                if (fv.resurlBack() == null || fv.resurlBack().isBlank()
                        || fv.resConfigFileName() == null || fv.resConfigFileName().isBlank()) {
                    throw new IllegalStateException("flashvars 缺 resurl_back/resConfigFileName");
                }
                String resBack = fv.resurlBack();
                String resXml = downloadXml(resBack + "/config/" + fv.resConfigFileName());

                Matcher m = ACT_LIST_TAG.matcher(resXml);
                if (!m.find()) {
                    throw new IllegalStateException("res_config 中无 activityListXML");
                }
                String tag = m.group();
                String listUrl = attr(tag, "url");
                String version = attr(tag, "version");
                if (listUrl == null || listUrl.isBlank()) {
                    throw new IllegalStateException("activityListXML 无 url 属性");
                }
                String fullUrl = resBack + "/" + listUrl + (version != null && !version.isBlank() ? "?version=" + version : "");
                String listXml = downloadXml(fullUrl);

                List<ActivityRow> rows = parser.parse(listXml);
                log.info("[活动] 用 server {} 抓到 {} 条活动", server, rows.size());
                return new FetchBundle(rows, resXml, resBack);
            } catch (Exception e) {
                log.warn("[活动] server {} 抓取失败: {}", server, e.getMessage());
                last = new IllegalStateException("server " + server + ": " + e.getMessage(), e);
            }
        }
        throw last != null ? last : new IllegalStateException("所有 server 抓取失败");
    }

    /** 下载并（按需）解密为 XML 文本。 */
    private String downloadXml(String url) throws Exception {
        byte[] raw = downloadBytes(url);
        byte[] xml = GameResourceDecryptor.looksLikePlainXml(raw) ? raw : GameResourceDecryptor.decrypt(raw).data();
        return new String(xml, StandardCharsets.UTF_8);
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " @ " + url);
        }
        return resp.body();
    }

    /** 从标签串里取某属性值（单/双引号兼容；缺失返回 null）。 */
    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }
}
