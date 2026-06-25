package com.rankharvester.net;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 按 {@link ProxyNodeStore} 里启用的 trojan 节点<strong>重新生成 sing-box 配置并热重载</strong>。
 *
 * <p>生成结构：{@code N} 个本地 SOCKS5 入站（端口 {@code [proxySocksPort, +portCount)}，与
 * {@link SingboxProxyService} 轮询一致）→ 每个节点一个出站（{@code trojan} 或 {@code anytls}，按节点 type 生成）
 * → 入站按轮询路由到不同节点（多出口 IP 分摊）→ {@code selector「proxy」} 作 {@code route.final} 兜底
 * + clash_api（供故障转移脚本）。无节点时全部直连（direct）。
 *
 * <p>热重载：写入配置文件后 {@code pkill -x sing-box}，由 super-entrypoint 看门狗 5s 内用新配置自动重启
 * （单容器同进程组）。仅当 {@code rankharvester.singbox.manage-enabled=true} 时真正写文件 + 重启；
 * 本地/dry-run 默认关闭，避免误写 {@code /etc/sing-box}。
 */
@Service
public class SingboxConfigService {

    private static final Logger log = LoggerFactory.getLogger(SingboxConfigService.class);
    private static final String SELECTOR_TAG = "proxy";
    private static final String DIRECT_TAG = "direct";

    private final ProxyNodeStore store;
    private final GameProperties gameProps;
    private final ObjectMapper mapper;
    private final boolean manageEnabled;
    private final Path configPath;
    private final String clashApiListen;

    public SingboxConfigService(ProxyNodeStore store, GameProperties gameProps, ObjectMapper mapper,
            SingboxProperties props) {
        this.store = store;
        this.gameProps = gameProps;
        this.mapper = mapper;
        this.manageEnabled = props.isManageEnabled();
        this.configPath = Path.of(props.getConfigPath());
        this.clashApiListen = props.getClashApiListen();
    }

    @PostConstruct
    void init() {
        if (!manageEnabled) {
            log.info("[singbox] 配置托管未启用（rankharvester.singbox.manage-enabled=false），跳过生成/重载");
            return;
        }
        log.info("[singbox] 配置托管已启用，启动时按 DB 节点重生成配置: {}", configPath);
        regenerateAndReload();
    }

    /** 按 DB 启用节点重生成 sing-box 配置文件并热重载（未启用托管则仅记录日志、不动文件）。 */
    public synchronized void regenerateAndReload() {
        if (!manageEnabled) {
            log.info("[singbox] 托管未启用，跳过重生成（节点改动已入库，启用托管后生效）");
            return;
        }
        List<ProxyNodeStore.Row> nodes = store.enabledNodes();
        String json;
        try {
            json = mapper.writeValueAsString(buildConfig(nodes));
        } catch (RuntimeException e) {
            log.error("[singbox] 生成配置 JSON 失败: {}", e.getMessage());
            return;
        }
        try {
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            log.info("[singbox] 已写入新配置（{} 个代理节点）→ {}", nodes.size(), configPath);
        } catch (Exception e) {
            log.error("[singbox] 写配置文件失败 {}: {}", configPath, e.getMessage());
            return;
        }
        reloadSingbox();
    }

    /** 触发 sing-box 重启以加载新配置（pkill 后由看门狗自动拉起）。best-effort。 */
    private void reloadSingbox() {
        try {
            Process p = new ProcessBuilder("pkill", "-x", "sing-box").redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            // pkill 退出码 1 = 没有匹配进程（首次启动可能 sing-box 尚未起），非错误。
            log.info("[singbox] 已触发 sing-box 重启（pkill 退出码={}）", p.exitValue());
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            log.warn("[singbox] 触发 sing-box 重启失败: {}", e.getMessage());
        }
    }

    /** 构建 sing-box 配置对象（legacy 1.12 字段风格）。 */
    private Map<String, Object> buildConfig(List<ProxyNodeStore.Row> nodes) {
        String listen = gameProps.getProxySocksHost() == null || gameProps.getProxySocksHost().isBlank()
                ? "127.0.0.1" : gameProps.getProxySocksHost().trim();
        int basePort = gameProps.getProxySocksPort();
        int count = Math.max(1, gameProps.getProxySocksPortCount());

        // 入站：count 个 socks
        var inbounds = new ArrayList<Map<String, Object>>();
        var inboundTags = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String tag = "in-" + i;
            inboundTags.add(tag);
            var in = new LinkedHashMap<String, Object>();
            in.put("type", "socks");
            in.put("tag", tag);
            in.put("listen", listen);
            in.put("listen_port", basePort + i);
            inbounds.add(in);
        }

        // 出站：每节点一个 trojan + selector + direct
        var outbounds = new ArrayList<Map<String, Object>>();
        var nodeTags = new ArrayList<String>();
        for (ProxyNodeStore.Row n : nodes) {
            String tag = "node-" + n.id();
            nodeTags.add(tag);
            outbounds.add("anytls".equals(n.type()) ? anytlsOutbound(tag, n) : trojanOutbound(tag, n));
        }
        if (!nodeTags.isEmpty()) {
            var selector = new LinkedHashMap<String, Object>();
            selector.put("type", "selector");
            selector.put("tag", SELECTOR_TAG);
            var selOut = new ArrayList<>(nodeTags);
            selOut.add(DIRECT_TAG);
            selector.put("outbounds", selOut);
            selector.put("default", nodeTags.get(0));
            outbounds.add(selector);
        }
        var direct = new LinkedHashMap<String, Object>();
        direct.put("type", "direct");
        direct.put("tag", DIRECT_TAG);
        outbounds.add(direct);

        // 路由：入站轮询到各节点（多出口 IP 分摊）；final 兜底用 selector（无节点则 direct）
        var rules = new ArrayList<Map<String, Object>>();
        if (!nodeTags.isEmpty()) {
            for (int i = 0; i < inboundTags.size(); i++) {
                var rule = new LinkedHashMap<String, Object>();
                rule.put("inbound", List.of(inboundTags.get(i)));
                rule.put("outbound", nodeTags.get(i % nodeTags.size()));
                rules.add(rule);
            }
        }
        var route = new LinkedHashMap<String, Object>();
        route.put("rules", rules);
        route.put("final", nodeTags.isEmpty() ? DIRECT_TAG : SELECTOR_TAG);

        var experimental = new LinkedHashMap<String, Object>();
        var clashApi = new LinkedHashMap<String, Object>();
        clashApi.put("external_controller", clashApiListen);
        experimental.put("clash_api", clashApi);

        var logBlock = new LinkedHashMap<String, Object>();
        logBlock.put("level", "warn");
        logBlock.put("timestamp", true);

        var root = new LinkedHashMap<String, Object>();
        root.put("log", logBlock);
        root.put("inbounds", inbounds);
        root.put("outbounds", outbounds);
        root.put("route", route);
        root.put("experimental", experimental);
        return root;
    }

    private static Map<String, Object> trojanOutbound(String tag, ProxyNodeStore.Row n) {
        var o = new LinkedHashMap<String, Object>();
        o.put("type", "trojan");
        o.put("tag", tag);
        o.put("server", n.server());
        o.put("server_port", n.port());
        o.put("password", n.password());
        if ("tls".equalsIgnoreCase(n.security())) {
            var tls = new LinkedHashMap<String, Object>();
            tls.put("enabled", true);
            String sni = (n.sni() != null && !n.sni().isBlank()) ? n.sni() : n.server();
            tls.put("server_name", sni);
            if (n.insecure()) {
                tls.put("insecure", true);
            }
            o.put("tls", tls);
        }
        return o;
    }

    /** anytls 出站（恒 TLS，sing-box ≥1.12）：sni 作 server_name，insecure 跳过证书校验。 */
    private static Map<String, Object> anytlsOutbound(String tag, ProxyNodeStore.Row n) {
        var o = new LinkedHashMap<String, Object>();
        o.put("type", "anytls");
        o.put("tag", tag);
        o.put("server", n.server());
        o.put("server_port", n.port());
        o.put("password", n.password());
        var tls = new LinkedHashMap<String, Object>();
        tls.put("enabled", true);
        String sni = (n.sni() != null && !n.sni().isBlank()) ? n.sni() : n.server();
        tls.put("server_name", sni);
        if (n.insecure()) {
            tls.put("insecure", true);
        }
        o.put("tls", tls);
        return o;
    }
}
