package com.rankharvester.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloudflare Turnstile 人机验证·服务端二次校验（替代原极验 Geetest）。
 *
 * <p>吃性能的操作（注册 / 留言 / 订阅 / 即刻抓取）前端先过 Turnstile 挑战拿到一次性 token
 * （请求体 {@code cfToken}），这里向 {@code challenges.cloudflare.com/turnstile/v0/siteverify}
 * 二次校验。Cloudflare 侧不可达时<b>放行</b>（fail-open），避免验证服务故障时全站不可用。
 */
@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);
    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final boolean enabled;
    private final String siteKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TurnstileService(
            @Value("${rankharvester.turnstile.enabled:true}") boolean enabled,
            @Value("${rankharvester.turnstile.site-key:}") String siteKey,
            @Value("${rankharvester.turnstile.secret-key:}") String secretKey) {
        this.enabled = enabled && !siteKey.isBlank() && !secretKey.isBlank();
        this.siteKey = siteKey;
        this.secretKey = secretKey;
        log.info("Turnstile 验证 {}（siteKey={}）", this.enabled ? "已启用" : "未启用", mask(siteKey));
    }

    public boolean enabled() {
        return enabled;
    }

    public String siteKey() {
        return siteKey;
    }

    /** 校验前端传来的一次性 token；未启用直接通过。Cloudflare 不可达=放行。 */
    public boolean verify(String token) {
        if (!enabled) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String form = "secret=" + enc(secretKey) + "&response=" + enc(token.trim());
            var req = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var node = JSON.readTree(resp.body());
            if (!node.path("success").asBoolean(false)) {
                log.info("Turnstile 校验未通过: {}", node.path("error-codes"));
                return false;
            }
            return true;
        } catch (Exception e) {
            // Cloudflare 侧异常 → 放行（避免验证服务故障时全站不可用）
            log.warn("Turnstile 校验请求异常，放行: {}", e.getMessage());
            return true;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String mask(String s) {
        return s == null || s.length() < 10 ? "?" : s.substring(0, 10) + "…";
    }
}
