package com.rankharvester.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 真实客户端 IP 解析（统一工具，全站限流/统计/留痕共用）。
 *
 * <p>链路：client → Cloudflare → nginx → 本服务。取值优先级：
 * <ol>
 *   <li>{@code CF-Connecting-IP}：Cloudflare 注入的真实访客 IP，<b>不可被客户端伪造</b>
 *       （CF 会覆盖同名入站头），最可信；</li>
 *   <li>{@code X-Forwarded-For} 的<b>最后一个</b>地址：nginx {@code proxy_add_x_forwarded_for}
 *       追加的直连方地址（经 CF 时为 CF 节点 IP——仍优于第一段）。
 *       注意<b>不能取第一段</b>：第一段可被客户端任意伪造，会被用来绕过按 IP 限流；</li>
 *   <li>{@code X-Real-IP}（nginx 设置）；</li>
 *   <li>TCP 对端地址兜底。</li>
 * </ol>
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.lastIndexOf(',');
            return (comma >= 0 ? xff.substring(comma + 1) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
