package com.rankharvester.net;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 {@code anytls://} 分享链接为节点字段。
 *
 * <p>格式：{@code anytls://<password>@<host>:<port>?sni=<sni>&insecure=1#<name>}。
 * anytls 协议<strong>恒走 TLS</strong>（{@code sni} 作 server_name，{@code insecure}/{@code allowInsecure}=1 跳过证书校验）。
 */
public final class AnytlsLink {

    private AnytlsLink() {}

    /** 解析结果。anytls 恒 TLS，故无 security 字段（生成出站时固定启用 tls）。 */
    public record Parsed(String name, String server, int port, String password,
            String sni, boolean insecure) {}

    /** 解析单条 anytls:// 链接；非法格式抛 {@link IllegalArgumentException}。 */
    public static Parsed parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("空链接");
        }
        String s = raw.trim();
        if (!s.toLowerCase().startsWith("anytls://")) {
            throw new IllegalArgumentException("不是 anytls:// 链接: " + s);
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("anytls 链接解析失败: " + e.getMessage());
        }
        String password = uri.getUserInfo();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("anytls 链接缺少密码: " + s);
        }
        password = URLDecoder.decode(password, StandardCharsets.UTF_8);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port <= 0) {
            throw new IllegalArgumentException("anytls 链接缺少 host/port: " + s);
        }
        var q = parseQuery(uri.getRawQuery());
        String sni = firstNonBlank(q.get("sni"), q.get("peer"), q.get("host"));
        boolean insecure = "1".equals(q.get("insecure")) || "true".equalsIgnoreCase(q.get("insecure"))
                || "1".equals(q.get("allowInsecure")) || "true".equalsIgnoreCase(q.get("allowInsecure"));
        String name = uri.getRawFragment() == null ? ""
                : URLDecoder.decode(uri.getRawFragment(), StandardCharsets.UTF_8);
        if (name.isBlank()) {
            name = host + ":" + port;
        }
        return new Parsed(name, host, port, password, sni, insecure);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var m = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return m;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                m.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
