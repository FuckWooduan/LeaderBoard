package com.rankharvester.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 极验 GeeTest v4（行为验证 4.0）·服务端二次校验。
 *
 * <p>与 Cloudflare Turnstile 并列，作为对中国用户更友好的备选验证（见 {@link CaptchaService}）。
 * 前端过完滑块拿到 {@code captcha.getValidate()}（含 lot_number / captcha_output / pass_token /
 * gen_time），整体 JSON 作为请求体字段 {@code gtPayload} 传来；这里用 {@code captchaKey} 对
 * {@code lot_number} 算 HMAC-SHA256 得 {@code sign_token}，POST 到 {@code gcaptcha4.geetest.com/validate}
 * 二次校验。极验侧不可达时<b>放行</b>（fail-open），与 Turnstile 行为一致，避免验证服务故障时全站不可用。
 */
@Service
public class GeetestService {

    private static final Logger log = LoggerFactory.getLogger(GeetestService.class);
    private static final String VALIDATE_URL = "https://gcaptcha4.geetest.com/validate";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final boolean enabled;
    private final String captchaId;
    private final String captchaKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GeetestService(
            @Value("${rankharvester.geetest.enabled:true}") boolean enabled,
            @Value("${rankharvester.geetest.captcha-id:}") String captchaId,
            @Value("${rankharvester.geetest.captcha-key:}") String captchaKey) {
        this.enabled = enabled && !captchaId.isBlank() && !captchaKey.isBlank();
        this.captchaId = captchaId;
        this.captchaKey = captchaKey;
        log.info("GeeTest 验证 {}（captchaId={}）", this.enabled ? "已启用" : "未启用", mask(captchaId));
    }

    public boolean enabled() {
        return enabled;
    }

    public String captchaId() {
        return captchaId;
    }

    /** 校验前端传来的 getValidate() JSON；未启用直接通过。极验不可达=放行。 */
    public boolean verify(String payloadJson) {
        if (!enabled) {
            return true;
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            return false;
        }
        try {
            JsonNode p = JSON.readTree(payloadJson);
            String lotNumber = p.path("lot_number").asString("");
            String captchaOutput = p.path("captcha_output").asString("");
            String passToken = p.path("pass_token").asString("");
            String genTime = p.path("gen_time").asString("");
            if (lotNumber.isBlank()) {
                log.info("GeeTest 校验缺少 lot_number");
                return false;
            }
            String signToken = hmacSha256Hex(lotNumber, captchaKey);
            String form = "lot_number=" + enc(lotNumber)
                    + "&captcha_output=" + enc(captchaOutput)
                    + "&pass_token=" + enc(passToken)
                    + "&gen_time=" + enc(genTime)
                    + "&sign_token=" + enc(signToken)
                    + "&captcha_id=" + enc(captchaId);
            var req = HttpRequest.newBuilder(URI.create(VALIDATE_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var node = JSON.readTree(resp.body());
            if (!"success".equals(node.path("result").asString(""))) {
                log.info("GeeTest 校验未通过: {}", node.path("reason"));
                return false;
            }
            return true;
        } catch (Exception e) {
            // 极验侧异常 → 放行（避免验证服务故障时全站不可用），与 Turnstile fail-open 一致
            log.warn("GeeTest 校验请求异常，放行: {}", e.getMessage());
            return true;
        }
    }

    private static String hmacSha256Hex(String message, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String mask(String s) {
        return s == null || s.length() < 10 ? "?" : s.substring(0, 10) + "…";
    }
}
