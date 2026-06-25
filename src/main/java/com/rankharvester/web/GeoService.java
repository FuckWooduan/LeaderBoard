package com.rankharvester.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * IP 地理定位（国家 / 省份），用于「仅中国 IP」与「按省限流」。
 *
 * <p>走 ip-api.com 免费接口（{@code http://ip-api.com/json/<ip>?fields=status,countryCode,regionName}），
 * 结果按 IP 在 Redis 缓存 24h。内网 / 回环地址视为本地（放行，省份记为「LOCAL」）。
 */
@Service
public class GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public GeoService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 国家码 + 省份。countryCode="CN" 表示中国大陆；本地地址返回 ("CN","LOCAL")。 */
    public record Geo(String countryCode, String province) {}

    public Geo lookup(String ip) {
        if (ip == null || ip.isBlank() || isLocal(ip)) {
            return new Geo("CN", "LOCAL");
        }
        String cacheKey = "geo:" + ip;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                int i = cached.indexOf('|');
                return new Geo(cached.substring(0, i), cached.substring(i + 1));
            }
        } catch (RuntimeException ignore) {
            // Redis 不可用时不缓存，继续查
        }
        Geo geo = fetch(ip);
        try {
            redis.opsForValue().set(cacheKey, geo.countryCode() + "|" + geo.province(), CACHE_TTL);
        } catch (RuntimeException ignore) {
            // best-effort
        }
        return geo;
    }

    private Geo fetch(String ip) {
        try {
            var req = HttpRequest.newBuilder(
                            URI.create("http://ip-api.com/json/" + ip + "?fields=status,countryCode,regionName"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode n = JSON.readTree(resp.body());
            if (!"success".equals(n.path("status").asString(""))) {
                return new Geo("", "");
            }
            return new Geo(n.path("countryCode").asString(""), n.path("regionName").asString(""));
        } catch (Exception e) {
            log.warn("[Geo] 查询失败 ip={}: {}", ip, e.getMessage());
            return new Geo("", ""); // 查不到 → 非 CN，调用方按未知处理
        }
    }

    private static boolean isLocal(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.startsWith("10.")
                || ip.startsWith("192.168.") || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.") || ip.startsWith("172.2")
                || ip.startsWith("172.30.") || ip.startsWith("172.31.") || ip.equals("0:0:0:0:0:0:0:1");
    }
}
