package com.rankharvester.web;

import com.rankharvester.net.AnytlsLink;
import com.rankharvester.net.ProxyNodeStore;
import com.rankharvester.net.SingboxConfigService;
import com.rankharvester.net.TrojanLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * sing-box 代理节点（trojan）后台管理。受 {@link AdminAuthFilter} 保护（{@code /api/boards/**} 需后台会话）。
 * 任何增删改后调用 {@link SingboxConfigService#regenerateAndReload()} 重生成配置并热重载 sing-box。
 */
@RestController
public class ProxyNodeController {

    private static final Logger log = LoggerFactory.getLogger(ProxyNodeController.class);

    private final ProxyNodeStore store;
    private final SingboxConfigService singbox;

    public ProxyNodeController(ProxyNodeStore store, SingboxConfigService singbox) {
        this.store = store;
        this.singbox = singbox;
    }

    @GetMapping("/api/boards/proxy-node/list")
    public Object list() {
        var rows = new ArrayList<Map<String, Object>>();
        for (ProxyNodeStore.Row r : store.all()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", r.id());
            m.put("type", r.type());
            m.put("name", r.name() == null ? "" : r.name());
            m.put("server", r.server());
            m.put("port", r.port());
            m.put("password", r.password() == null ? "" : r.password());
            m.put("security", r.security() == null ? "none" : r.security());
            m.put("sni", r.sni() == null ? "" : r.sni());
            m.put("insecure", r.insecure());
            m.put("enabled", r.enabled());
            m.put("updatedAt", r.updatedAt());
            rows.add(m);
        }
        return Map.of("nodes", rows);
    }

    /** 新增/编辑一个节点（手填字段）。 */
    @PostMapping("/api/boards/proxy-node/save")
    public Object save(@RequestBody Map<String, Object> body) {
        String server = str(body.get("server"));
        int port = (int) reqLong(body.get("port"));
        if (server.isEmpty() || port <= 0) {
            return Map.of("ok", false, "error", "server / port 不能为空");
        }
        String type = ProxyNodeStore.normalizeType(str(body.get("type")));
        String name = str(body.get("name"));
        String password = str(body.get("password"));
        // anytls 恒 TLS；trojan 看 security 字段。
        String security = ("anytls".equals(type) || "tls".equalsIgnoreCase(str(body.get("security")))) ? "tls" : "none";
        String sni = str(body.get("sni"));
        boolean insecure = boolOr(body.get("insecure"), false);
        boolean enabled = boolOr(body.get("enabled"), true);
        Long id = optLong(body.get("id"));
        if (id == null) {
            store.create(type, name, server, port, password, security, sni, insecure, enabled);
        } else {
            store.update(id, type, name, server, port, password, security, sni, insecure, enabled);
        }
        singbox.regenerateAndReload();
        return Map.of("ok", true);
    }

    /**
     * 批量导入 {@code trojan://} / {@code anytls://} 链接：{links:"每行一条"} 或 {items:[...]}。
     * 可选 {@code enabled}（默认 true）：导入后是否启用——传 false 可先全部导入停用，再单独启用挑选的节点。
     */
    @PostMapping("/api/boards/proxy-node/import")
    public Object importLinks(@RequestBody Map<String, Object> body) {
        var lines = new ArrayList<String>();
        if (body.get("links") instanceof String s) {
            for (String line : s.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        if (body.get("items") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    lines.add(o.toString().trim());
                }
            }
        }
        if (lines.isEmpty()) {
            return Map.of("ok", false, "error", "没有可导入的链接");
        }
        boolean enabled = boolOr(body.get("enabled"), true);
        int imported = 0;
        var errors = new ArrayList<String>();
        for (String line : lines) {
            try {
                if (line.toLowerCase().startsWith("anytls://")) {
                    AnytlsLink.Parsed p = AnytlsLink.parse(line);
                    store.create("anytls", p.name(), p.server(), p.port(), p.password(), "tls", p.sni(), p.insecure(), enabled);
                } else {
                    TrojanLink.Parsed p = TrojanLink.parse(line);
                    store.create("trojan", p.name(), p.server(), p.port(), p.password(), p.security(), p.sni(), p.insecure(), enabled);
                }
                imported++;
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
                log.warn("[proxy-node] 导入失败: {}", e.getMessage());
            }
        }
        if (imported > 0) {
            singbox.regenerateAndReload();
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", imported > 0);
        out.put("imported", imported);
        out.put("errors", errors);
        return out;
    }

    @PostMapping("/api/boards/proxy-node/delete")
    public Object delete(@RequestBody Map<String, Object> body) {
        store.delete(reqLong(body.get("id")));
        singbox.regenerateAndReload();
        return Map.of("ok", true);
    }

    @PostMapping("/api/boards/proxy-node/enable")
    public Object enable(@RequestBody Map<String, Object> body) {
        store.setEnabled(reqLong(body.get("id")), boolOr(body.get("enabled"), true));
        singbox.regenerateAndReload();
        return Map.of("ok", true);
    }

    // ───────────── 入参解析 ─────────────

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static Long optLong(Object v) {
        if (v == null || str(v).isEmpty()) {
            return null;
        }
        return reqLong(v);
    }

    private static long reqLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(str(v));
    }

    private static boolean boolOr(Object v, boolean dft) {
        if (v == null) {
            return dft;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = str(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
