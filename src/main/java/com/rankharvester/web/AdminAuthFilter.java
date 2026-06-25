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
 * 后台鉴权过滤器：保护 {@code /api/boards/**}（字段配置、开关、刷新等管理 API）。
 *
 * <p>两种合法身份（任一即可）：
 * <ol>
 *   <li>站长 TOTP 会话：请求头 {@code X-Admin-Token} 或 cookie {@code admin_token}；</li>
 *   <li>管理员账号会话（邀请制注册）：请求头 {@code X-User-Token} 或 cookie {@code user_token}，
 *       且账号 role=ADMIN。</li>
 * </ol>
 *
 * <p>不拦截：登录接口 {@code /api/admin/login}、{@code /api/auth/**}、公开读 {@code /api/public/**}、
 * 前端静态页（页面自身先登录拿 token，再带 token 调受保护 API）。
 */
@Component
@Order(1)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminAuthService auth;
    private final UserAuthService userAuth;

    public AdminAuthFilter(AdminAuthService auth, UserAuthService userAuth) {
        this.auth = auth;
        this.userAuth = userAuth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path != null && path.startsWith("/api/boards")) {
            boolean ok = auth.valid(tokenOf(req)) || userAuth.isAdmin(UserAuthController.userTokenOf(req));
            if (!ok) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static String tokenOf(HttpServletRequest req) {
        String h = req.getHeader("X-Admin-Token");
        if (h != null && !h.isBlank()) {
            return h;
        }
        if (req.getCookies() != null) {
            for (var c : req.getCookies()) {
                if ("admin_token".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
