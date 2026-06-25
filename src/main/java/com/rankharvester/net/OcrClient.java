package com.rankharvester.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 验证码 OCR 客户端（对接自建 FastAPI 服务，{@code POST /} multipart 字段 {@code image}）。
 *
 * <p>响应 JSON：{@code {"success":true,"taskid":"...","code":"abcd"}}。失败返回 null（不阻塞登录主流程）。
 */
public final class OcrClient {

    private static final Logger log = LoggerFactory.getLogger(OcrClient.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String endpoint;
    private final HttpClient http;

    public OcrClient(String endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 识别 PNG 验证码，返回小写文本；失败返回 null。 */
    public String recognize(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }
        try {
            String boundary = "Boundary-" + UUID.randomUUID();
            byte[] body = multipart(pngBytes, boundary);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[OCR] HTTP {}：{}", resp.statusCode(), resp.body());
                return null;
            }
            var n = MAPPER.readTree(resp.body());
            if (!n.path("success").asBoolean(false)) {
                log.warn("[OCR] 识别未成功：{}", resp.body());
                return null;
            }
            String code = n.path("code").asString("");
            return code.isBlank() ? null : code.toLowerCase();
        } catch (Exception e) {
            log.warn("[OCR] 识别异常：{}", e.toString());
            return null;
        }
    }

    private static byte[] multipart(byte[] png, String boundary) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"captcha.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] t = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + png.length + t.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(png, 0, out, h.length, png.length);
        System.arraycopy(t, 0, out, h.length + png.length, t.length);
        return out;
    }
}
