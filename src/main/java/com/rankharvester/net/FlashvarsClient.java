package com.rankharvester.net;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 大厅 flashvars 抓取（移植自 Engine FlashvarsService，<b>去代理、直连</b>）。
 *
 * <p>四步重定向链：play-sid-{id}(带 Pauth) → loginUrl → lobby.php → lobby_truly.php(带微端 cookie) → 解析 flashvars。
 */
public final class FlashvarsClient {

    private static final Logger log = LoggerFactory.getLogger(FlashvarsClient.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String PLAY_URL_TEMPLATE = "https://my.4399.com/yxssjj/play-sid-%d";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:56.0) Gecko/20100101 Firefox/56.0";
    private static final Pattern FLASHVAR = Pattern.compile("flashvars\\.(\\w+)\\s*=\\s*'([^']*)';");
    private static final String STOP_MARKER = "wdapp.isAirMicroClient";

    private final String microVersion;
    private final String deviceInfo;
    private final HttpClient http;

    public FlashvarsClient(String microVersion, String deviceInfo) {
        this.microVersion = microVersion;
        this.deviceInfo = deviceInfo;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // 关键：避免对 http:// 触发 h2c 升级导致 4399 返回 200
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** flashvars 关键字段。{@code resurlBack}/{@code resConfigFileName} 用于下载游戏资源(GameData bin)。 */
    public record FlashvarsData(
            String username, String timestamp, String sign, String isadult, String isMicroClient,
            String loginUrl, String clientIp, String resurlBack, String resConfigFileName) {}

    public FlashvarsData fetch(String pauth, int serverId) {
        if (pauth == null || pauth.isBlank()) {
            throw new IllegalStateException("账号无 pauth，无法获取 flashvars");
        }
        try {
            // 1) play-sid → loginUrl（Location 可能是相对地址，按请求 URI 解析为绝对）
            String playUrl = String.format(PLAY_URL_TEMPLATE, serverId);
            String loginUrl = resolve(playUrl, location(get(playUrl, "Cookie", "Pauth=" + pauth), "第一次重定向"));
            // 2) loginUrl → lobbyUrl + 基础 cookie
            HttpResponse<Void> r2 = get(loginUrl);
            // 新区（如双线六区，district 46）登录会返回 200「选择 生死狙击1 / RPG2」分支页而非 302。
            // 选普通生死狙击（rpgType=1）重发一次即拿到 302 跳大厅；RPG2 是 rpgType=2（另一套 s990 大厅，非排行榜所需）。
            if (r2.statusCode() == 200 && r2.headers().firstValue("location").isEmpty()) {
                loginUrl = loginUrl + (loginUrl.contains("?") ? "&" : "?") + "rpgType=1";
                r2 = get(loginUrl);
            }
            String lobbyUrl = resolve(loginUrl, location(r2, "第二次重定向"));
            String baseCookies = cookieHeader(r2);
            // 3) lobby.php 初始化
            get(lobbyUrl, "Cookie", baseCookies);
            // 4) lobby_truly.php → HTML
            String trulyUrl = buildTrulyUrl(lobbyUrl);
            String fullCookie = microCookies() + (baseCookies.isEmpty() ? "" : "; " + baseCookies);
            HttpResponse<String> r4 = http.send(
                    HttpRequest.newBuilder(URI.create(trulyUrl))
                            .header("User-Agent", USER_AGENT)
                            .header("Cookie", fullCookie)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r4.statusCode() != 200) {
                throw new IllegalStateException("lobby_truly 状态码非 200: " + r4.statusCode());
            }
            return parse(r4.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("flashvars 抓取被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("flashvars 抓取网络失败: " + e.getMessage(), e);
        }
    }

    private FlashvarsData parse(String html) {
        var params = new LinkedHashMap<String, String>();
        for (String line : html.lines().toList()) {
            if (line.contains(STOP_MARKER)) break;
            Matcher m = FLASHVAR.matcher(line);
            if (m.find()) {
                params.put(m.group(1), URLDecoder.decode(m.group(2), StandardCharsets.UTF_8));
            }
        }
        if (params.isEmpty()) {
            throw new IllegalStateException("未解析到 flashvars（pauth 可能已失效）");
        }
        String loginUrl = "";
        String clientIp = "";
        String ext = params.get("extParams");
        if (ext != null && !ext.isBlank()) {
            try {
                var tree = MAPPER.readTree(ext);
                loginUrl = tree.path("loginUrl").asString("");
                clientIp = tree.path("clientIp").asString("");
            } catch (Exception e) {
                log.warn("解析 extParams 失败: {}", e.getMessage());
            }
        }
        return new FlashvarsData(
                params.get("username"), params.get("timestamp"), params.get("sign"),
                params.get("isadult"), params.getOrDefault("isMicroClient", "1"), loginUrl, clientIp,
                params.get("resurl_back"), params.get("resConfigFileName"));
    }

    private HttpResponse<Void> get(String url, String... headers) throws java.io.IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String location(HttpResponse<?> resp, String step) {
        return resp.headers().firstValue("location")
                .orElseThrow(() -> new IllegalStateException(step + "无 Location，状态码 " + resp.statusCode()));
    }

    /**
     * 把可能是相对路径的 Location 解析为绝对 URL。
     * 绝对地址原样返回，避免 {@link URI#resolve} 归一化破坏已编码参数（如 site 的 %252F）。
     */
    private static String resolve(String base, String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        return URI.create(base).resolve(location).toString();
    }

    private static String cookieHeader(HttpResponse<?> resp) {
        return resp.headers().allValues("set-cookie").stream()
                .map(c -> c.split(";", 2)[0])
                .collect(Collectors.joining("; "));
    }

    private String microCookies() {
        return "ssjj_isMicroClient=1; ssjj_microVersion=" + microVersion
                + "; ssjj_deviceInfo=" + deviceInfo + "; ssjj_lobbyScale=1";
    }

    private static String buildTrulyUrl(String lobbyUrl) {
        String truly = lobbyUrl.replace("lobby.php", "lobby_truly.php");
        return truly + (truly.contains("?") ? "&" : "?") + "_weapp=1";
    }
}
