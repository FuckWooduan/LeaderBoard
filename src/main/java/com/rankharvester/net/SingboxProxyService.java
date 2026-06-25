package com.rankharvester.net;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sing-box 本地 SOCKS5 出口提供器（彻底取代已废弃的熊猫代理）。
 *
 * <p>游戏 TCP 登录走本地 sing-box 的多个 SOCKS5 端口（香港住宅出口），按端口轮询不同出口 IP，
 * 绕过海外机房 IP 被 4399 判为异常（loginV3 rc=4）。无第三方 API、无鉴权、无缓存。
 * 配置：{@code rankharvester.game.proxy-enabled} + {@code proxy-socks-host/port/port-count}。
 */
public final class SingboxProxyService {

    private static final Logger log = LoggerFactory.getLogger(SingboxProxyService.class);

    private final boolean enabled;
    private final String socksHost;
    private final int socksPort;
    private final int socksPortCount;
    private final AtomicInteger rr = new AtomicInteger();

    public SingboxProxyService(GameProperties props) {
        this.socksHost = props.getProxySocksHost() == null ? "" : props.getProxySocksHost().trim();
        this.socksPort = props.getProxySocksPort();
        this.socksPortCount = Math.max(1, props.getProxySocksPortCount());
        this.enabled = props.isProxyEnabled() && !socksHost.isBlank();
        if (props.isProxyEnabled() && socksHost.isBlank()) {
            log.warn("代理已启用但未配置 sing-box 出口（rankharvester.game.proxy-socks-host 为空），将直连不走代理");
        } else if (enabled) {
            log.info("游戏 TCP 代理：sing-box 本地 SOCKS5 {}:{}..{}（{} 个出口端口轮询）",
                    socksHost, socksPort, socksPort + socksPortCount - 1, socksPortCount);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 取一个 SOCKS5 出口（按端口轮询不同住宅 IP，无鉴权）。未启用返回 null。 */
    public ProxyEndpoint current() {
        if (!enabled) {
            return null;
        }
        int port = socksPort + Math.floorMod(rr.getAndIncrement(), socksPortCount);
        return new ProxyEndpoint(socksHost, port, null, null);
    }

    /** 换下一个出口端口（当前代理连不通时调用）。 */
    public ProxyEndpoint refresh() {
        return current();
    }
}
