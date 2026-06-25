package com.rankharvester.net;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 {@code trojan://} 分享链接为节点字段。
 *
 * <p>格式：{@code trojan://<password>@<host>:<port>?security=none|tls&sni=<sni>&allowInsecure=1#<name>}。
 * {@code security=none}（或缺省/无 tls 字段）= 裸 TCP trojan（不启用 TLS）；{@code security=tls} = 启用 TLS。
 */
public final class TrojanLink {

    private TrojanLink() {}

    /** 解析结果。{@code security}=「none」|「tls」。 */
    public record Parsed(String name, String server, int port, String password,
            String security, String sni, boolean insecure) {}

    /** 解析单条 trojan:// 链接；非法格式抛 {@link IllegalArgumentException}。 */
    public static Parsed parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("空链接");
        }
        String s = raw.trim();
        if (!s.toLowerCase().startsWith("trojan://")) {
            throw new IllegalArgumentException("不是 trojan:// 链接: " + s);
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("trojan 链接解析失败: " + e.getMessage());
        }
        String password = uri.getUserInfo();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("trojan 链接缺少密码: " + s);
        }
        password = URLDecoder.decode(password, StandardCharsets.UTF_8);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port <= 0) {
            throw new IllegalArgumentException("trojan 链接缺少 host/port: " + s);
        }
        var q = parseQuery(uri.getRawQuery());
        String securityRaw = q.getOrDefault("security", "").toLowerCase();
        String security = "tls".equals(securityRaw) ? "tls" : "none";
        String sni = firstNonBlank(q.get("sni"), q.get("peer"), q.get("host"));
        boolean insecure = "1".equals(q.get("allowInsecure")) || "true".equalsIgnoreCase(q.get("allowInsecure"))
                || "1".equals(q.get("insecure")) || "true".equalsIgnoreCase(q.get("insecure"));
        String name = uri.getRawFragment() == null ? ""
                : URLDecoder.decode(uri.getRawFragment(), StandardCharsets.UTF_8);
        if (name.isBlank()) {
            name = host + ":" + port;
        }
        return new Parsed(name, host, port, password, security, sni, insecure);
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
