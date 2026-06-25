package com.rankharvester.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 动态接口禁缓存：给「后台管理 + 配置驱动」的 JSON 接口加 {@code Cache-Control: no-store}，
 * 防止前置 nginx proxy_cache 把它们缓存成旧值。
 *
 * <p>根因：源站对这些接口不发 Cache-Control，宝塔 nginx 便按自身 proxy_cache_valid 缓存了 200 响应，
 * 导致后台保存（写入成功）后刷新读到 STALE 旧数据，表现为「保存没有任何作用」。
 *
 * <p>仅覆盖必须实时的低频接口：
 * <ul>
 *   <li>{@code /api/boards/**}：后台所有读写。</li>
 *   <li>{@code /api/public/slice/**}：分区榜列表/覆盖（后台与前台 /slice 都依赖，配置改完要立刻可见）。</li>
 *   <li>{@code /api/public/visit}：首屏访问/接口调用统计（含今日计数）。</li>
 * </ul>
 * 高频榜单数据接口不在此列，仍可被缓存以减轻源站压力。
 */
@Component
@Order(0)
public class DynamicApiCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path != null
                && (path.startsWith("/api/boards/")
                        || path.startsWith("/api/public/slice/")
                        || path.equals("/api/public/visit"))) {
            res.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(req, res);
    }
}
