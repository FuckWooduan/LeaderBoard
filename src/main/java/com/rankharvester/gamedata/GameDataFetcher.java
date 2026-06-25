package com.rankharvester.gamedata;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.net.FlashvarsClient;
import com.rankharvester.net.GameServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GameData 抓取（移植自 StrikeGod GameDataSyncService 的下载/解密部分）。
 *
 * <p>流程：取可用账号 pauth → flashvars(含 resurl_back/resConfigFileName) → 下载 res_config.xml →
 * 正则取 gameDataBin/gameDataOtherBin 的 url → 下载 .bin → {@link GameResourceDecryptor#decryptBin} 解出 JSON →
 * 顶层 container（含 shop_weapon 等静态表）。直连游戏 CDN，不走代理。
 */
@Component
public class GameDataFetcher {

    private static final Logger log = LoggerFactory.getLogger(GameDataFetcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern GAME_DATA_BIN = Pattern.compile(
            "<gameDataBin[^>]*url=[\"']([^\"']+)[\"'][^>]*md5=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern GAME_DATA_OTHER_BIN = Pattern.compile(
            "<gameDataOtherBin[^>]*url=[\"']([^\"']+)[\"'][^>]*md5=[\"']([^\"']+)[\"'][^>]*>");

    private final AccountRegistry accounts;
    private final FlashvarsClient flashvars;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GameDataFetcher(AccountRegistry accounts, FlashvarsClient flashvars) {
        this.accounts = accounts;
        this.flashvars = flashvars;
    }

    /** GameData / GameDataOther 两个 bin 解出的顶层 container（任一可能为 null）。 */
    public record GameData(JsonNode gameData, JsonNode gameDataOther) {}

    /** 抓取并解密 GameData（用库里账号的 pauth）。失败抛 {@link IllegalStateException}。 */
    public GameData fetch() {
        var account = accounts.all().stream()
                .filter(a -> a.enabled() && a.available()
                        && a.pauth() != null && !a.pauth().isBlank() && !a.servers().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用账号(带 pauth)"));
        return fetch(account.pauth(), account.servers());
    }

    /** 抓取并解密 GameData（显式 pauth + 候选区服列表，依次尝试）。 */
    public GameData fetch(String pauth, java.util.List<String> servers) {
        if (pauth == null || pauth.isBlank()) {
            throw new IllegalStateException("pauth 为空");
        }
        if (servers == null || servers.isEmpty()) {
            servers = java.util.List.of("6", "5", "1"); // 兜底：电信三区/双线一区等几个登录区
        }
        IllegalStateException last = null;
        for (String server : servers) {
            try {
                int serverId = GameServer.fromIdOrThrow(Integer.parseInt(server.trim())).id();
                var fv = flashvars.fetch(pauth, serverId);
                if (fv.resurlBack() == null || fv.resurlBack().isBlank()
                        || fv.resConfigFileName() == null || fv.resConfigFileName().isBlank()) {
                    throw new IllegalStateException("flashvars 缺 resurl_back/resConfigFileName");
                }
                String resCfgUrl = fv.resurlBack() + "/config/" + fv.resConfigFileName();
                byte[] resCfg = downloadBytes(resCfgUrl);
                byte[] xmlBytes = GameResourceDecryptor.looksLikePlainXml(resCfg)
                        ? resCfg : GameResourceDecryptor.decrypt(resCfg).data();
                String xml = new String(xmlBytes, StandardCharsets.UTF_8);

                JsonNode gd = fetchBin(xml, fv.resurlBack(), GAME_DATA_BIN, "GameData");
                JsonNode gdo = fetchBin(xml, fv.resurlBack(), GAME_DATA_OTHER_BIN, "GameDataOther");
                if (gd == null && gdo == null) {
                    throw new IllegalStateException("GameData/GameDataOther 两个 bin 都解析失败");
                }
                log.info("[GameData] 用 server {} 完成 GameData 下载（gd={}, gdo={}）",
                        server, gd != null, gdo != null);
                return new GameData(gd, gdo);
            } catch (Exception e) {
                log.warn("[GameData] server {} 下载失败: {}", server, e.getMessage());
                last = new IllegalStateException("server " + server + ": " + e.getMessage(), e);
            }
        }
        throw last != null ? last : new IllegalStateException("所有 server 下载失败");
    }

    /**
     * 下载并解出某个<strong>命名 XML 配置资源</strong>（如 {@code mapConfigXML} / {@code gameModeXML}，
     * 它们与 {@code gameDataBin} 同为 res_config.xml 的兄弟项）。用库里可用账号的 pauth；
     * <strong>尽力而为</strong>：任何失败返回 {@code null}（名称解析降级为数字 ID，不阻塞功能）。
     */
    public String fetchConfigXml(String tag) {
        try {
            var account = accounts.all().stream()
                    .filter(a -> a.enabled() && a.available()
                            && a.pauth() != null && !a.pauth().isBlank() && !a.servers().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (account == null) {
                log.warn("[GameData] 取配置 {} 失败：无可用账号(带 pauth)", tag);
                return null;
            }
            return fetchConfigXml(account.pauth(), account.servers(), tag);
        } catch (Exception e) {
            log.warn("[GameData] 取配置 {} 异常: {}", tag, e.getMessage());
            return null;
        }
    }

    /** 见 {@link #fetchConfigXml(String)}：显式 pauth + 候选区服，依次尝试。失败返回 {@code null}。 */
    public String fetchConfigXml(String pauth, java.util.List<String> servers, String tag) {
        if (pauth == null || pauth.isBlank()) {
            return null;
        }
        if (servers == null || servers.isEmpty()) {
            servers = java.util.List.of("6", "5", "1");
        }
        Pattern urlPat = Pattern.compile("<" + Pattern.quote(tag) + "[^>]*url=[\"']([^\"']+)[\"']");
        for (String server : servers) {
            try {
                int serverId = GameServer.fromIdOrThrow(Integer.parseInt(server.trim())).id();
                var fv = flashvars.fetch(pauth, serverId);
                if (fv.resurlBack() == null || fv.resurlBack().isBlank()
                        || fv.resConfigFileName() == null || fv.resConfigFileName().isBlank()) {
                    continue;
                }
                byte[] resCfg = downloadBytes(fv.resurlBack() + "/config/" + fv.resConfigFileName());
                byte[] resXml = GameResourceDecryptor.looksLikePlainXml(resCfg)
                        ? resCfg : GameResourceDecryptor.decrypt(resCfg).data();
                Matcher m = urlPat.matcher(new String(resXml, StandardCharsets.UTF_8));
                if (!m.find()) {
                    log.warn("[GameData] res_config 中无 {}", tag);
                    return null;
                }
                byte[] raw = downloadBytes(fv.resurlBack() + "/" + m.group(1));
                byte[] cfg = GameResourceDecryptor.looksLikePlainXml(raw)
                        ? raw : GameResourceDecryptor.decrypt(raw).data();
                log.info("[GameData] 用 server {} 取到配置 {}（{} 字节）", server, tag, cfg.length);
                return new String(cfg, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("[GameData] 取配置 {} @server {} 失败: {}", tag, server, e.getMessage());
            }
        }
        return null;
    }

    private JsonNode fetchBin(String xml, String resurl, Pattern pattern, String name) {
        Matcher m = pattern.matcher(xml);
        if (!m.find()) {
            log.warn("[GameData] res_config 中无 {}Bin", name);
            return null;
        }
        String binUrl = m.group(1);
        try {
            byte[] bin = downloadBytes(resurl + "/" + binUrl);
            var dec = GameResourceDecryptor.decryptBin(bin);
            if (!dec.success()) {
                log.warn("[GameData] {} .bin 解密失败", name);
                return null;
            }
            JsonNode root = JSON.readTree(new String(dec.data(), StandardCharsets.UTF_8));
            return root.isArray() && !root.isEmpty() ? root.get(0) : (root.isObject() ? root : null);
        } catch (Exception e) {
            log.warn("[GameData] {} 下载/解析异常: {}", name, e.getMessage());
            return null;
        }
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

    /** 在 GameData/GameDataOther 任一 container 中找 shop_weapon 表（对象）。 */
    public static JsonNode shopWeapon(GameData gd) {
        for (JsonNode c : new JsonNode[]{gd.gameData(), gd.gameDataOther()}) {
            if (c == null) continue;
            for (String key : new String[]{"shop_weapon", "shopWeapon"}) {
                JsonNode n = c.get(key);
                if (n != null && n.isObject() && !n.isEmpty()) return n;
            }
        }
        return null;
    }
}
