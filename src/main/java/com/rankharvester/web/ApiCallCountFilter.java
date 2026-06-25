package com.rankharvester.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统计「全部 API 调用次数」(total)：每个 {@code /api/} 请求计一次（前端轮询类接口排除，避免噪声刷量）。
 * API key 通道（/api/key/**）鉴权通过时另由 {@link ApiKeyController} 计入 internal。
 * 前端「接口调用 内部/总共」即 internal / total。
 */
@Component
@Order(1)
public class ApiCallCountFilter extends OncePerRequestFilter {

    /** 前端自身轮询/握手类接口：不计入总数，否则总数会被页面轮询淹没。 */
    private static final Set<String> EXCLUDE = Set.of(
            "/api/public/visit",
            "/api/public/fetch-activity",
            "/api/public/captcha-config");

    private final VisitStatsService stats;

    public ApiCallCountFilter(VisitStatsService stats) {
        this.stats = stats;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path != null && path.startsWith("/api/") && !EXCLUDE.contains(path)) {
            stats.incrApiTotal();
        }
        chain.doFilter(req, res);
    }
}
