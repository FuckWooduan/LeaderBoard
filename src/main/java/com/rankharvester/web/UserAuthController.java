package com.rankharvester.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点账号 API。
 *
 * <ul>
 *   <li>{@code POST /api/auth/register} 注册（极验；带 ADMIN 邀请码则注册成管理员）</li>
 *   <li>{@code POST /api/auth/login}    登录</li>
 *   <li>{@code POST /api/auth/logout}   退出</li>
 *   <li>{@code GET  /api/auth/me}       当前会话</li>
 *   <li>{@code GET/POST /api/boards/invites/**}、{@code /api/boards/users/**} 管理端（受 Admin 鉴权保护）</li>
 * </ul>
 *
 * <p>会话 token 同时写 HttpOnly cookie {@code user_token}（30 天）并随响应体返回（前端可放
 * {@code X-User-Token} 头，两者任一即可）。
 */
@RestController
public class UserAuthController {

    static final String COOKIE = "user_token";
    private static final int COOKIE_MAX_AGE = 30 * 24 * 3600;

    private final UserAuthService auth;
    private final CaptchaService captcha;
    private final RateLimitService rateLimit;

    public UserAuthController(UserAuthService auth, CaptchaService captcha, RateLimitService rateLimit) {
        this.auth = auth;
        this.captcha = captcha;
        this.rateLimit = rateLimit;
    }

    public record RegisterReq(String username, String password, String email, String inviteCode,
                              String cfToken, String gtPayload) {}

    public record LoginReq(String username, String password) {}

    @PostMapping("/api/auth/register")
    public Map<String, Object> register(@RequestBody RegisterReq req, HttpServletRequest http,
                                        HttpServletResponse res) {
        String ip = ClientIp.of(http);
        if (!rateLimit.allowPerDay("auth:reg:" + ip, 5)) {
            return Map.of("ok", false, "message", "今日注册次数过多，请明天再试");
        }
        if (!captcha.verify(req.cfToken(), req.gtPayload())) {
            return Map.of("ok", false, "message", "请完成人机验证");
        }
        var r = auth.register(req.username(), req.password(), req.email(), req.inviteCode(), ip);
        return finishLogin(r, res);
    }

    /** 邮箱验证落地页（前台 /verify）调用：凭 token 标记验证。 */
    @PostMapping("/api/auth/verify-email")
    public Map<String, Object> verifyEmail(@RequestBody Map<String, Object> body) {
        String token = body == null ? "" : String.valueOf(body.getOrDefault("token", ""));
        boolean ok = auth.verifyEmail(token);
        return ok ? Map.of("ok", true, "message", "邮箱验证成功！")
                : Map.of("ok", false, "message", "验证链接无效或已使用");
    }

    /** 重发验证邮件（需登录）。 */
    @PostMapping("/api/auth/resend-verify")
    public Map<String, Object> resendVerify(HttpServletRequest req) {
        var s = auth.session(tokenOf(req));
        if (s == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        var r = auth.resendVerify(s.userId());
        return Map.of("ok", r.ok(), "message", r.message());
    }

    public record ForgotReq(String username, String cfToken, String gtPayload) {}

    public record ResetPasswordReq(String token, String newPassword) {}

    public record DeleteAccountReq(String password) {}

    /** 找回密码：发重置邮件（防枚举，一律返回成功文案）。 */
    @PostMapping("/api/auth/forgot")
    public Map<String, Object> forgot(@RequestBody ForgotReq req, HttpServletRequest http) {
        String ip = ClientIp.of(http);
        if (!rateLimit.allowPerDay("auth:forgot:" + ip, 5)) {
            return Map.of("ok", false, "message", "今日找回次数过多，请明天再试");
        }
        if (!captcha.verify(req.cfToken(), req.gtPayload())) {
            return Map.of("ok", false, "message", "请完成人机验证");
        }
        var r = auth.forgotPassword(req.username());
        return Map.of("ok", r.ok(), "message", r.message());
    }

    /** 找回密码落地页提交：凭重置 token 设新密码。 */
    @PostMapping("/api/auth/reset-password")
    public Map<String, Object> resetPassword(@RequestBody ResetPasswordReq req, HttpServletRequest http) {
        if (!rateLimit.allowPerHour("auth:reset:" + ClientIp.of(http), 10)) {
            return Map.of("ok", false, "message", "操作过于频繁，请稍后再试");
        }
        var r = auth.resetPassword(req.token(), req.newPassword());
        return Map.of("ok", r.ok(), "message", r.message());
    }

    /** 退出其他全部设备（保留当前会话）。 */
    @PostMapping("/api/auth/logout-others")
    public Map<String, Object> logoutOthers(HttpServletRequest http) {
        String token = tokenOf(http);
        var s = auth.session(token);
        if (s == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        var r = auth.logoutOthers(s.userId(), token);
        return Map.of("ok", r.ok(), "message", r.message());
    }

    /** 注销账号（软删除，需密码确认）。 */
    @PostMapping("/api/auth/delete-account")
    public Map<String, Object> deleteAccount(@RequestBody DeleteAccountReq req, HttpServletRequest http,
                                             HttpServletResponse res) {
        var s = auth.session(tokenOf(http));
        if (s == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        if (!rateLimit.allowPerDay("auth:delacct:" + ClientIp.of(http), 5)) {
            return Map.of("ok", false, "message", "操作过于频繁，请明天再试");
        }
        var r = auth.deleteAccount(s.userId(), req.password());
        if (r.ok()) {
            var cookie = new Cookie(COOKIE, "");
            cookie.setPath("/");
            cookie.setMaxAge(0);
            cookie.setHttpOnly(true);
            res.addCookie(cookie);
        }
        return Map.of("ok", r.ok(), "message", r.message());
    }

    public record ChangePasswordReq(String oldPassword, String newPassword) {}

    public record ChangeEmailReq(String password, String email) {}

    /** 改密码（需登录；校验旧密码；改后踢其他设备）。 */
    @PostMapping("/api/auth/change-password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordReq req, HttpServletRequest http) {
        String token = tokenOf(http);
        var s = auth.session(token);
        if (s == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        if (!rateLimit.allowPerHour("auth:chpw:" + ClientIp.of(http), 10)) {
            return Map.of("ok", false, "message", "操作过于频繁，请稍后再试");
        }
        var r = auth.changePassword(s.userId(), req.oldPassword(), req.newPassword(), token);
        return Map.of("ok", r.ok(), "message", r.message());
    }

    /** 换绑邮箱（需登录；校验密码；新邮箱需重新验证）。 */
    @PostMapping("/api/auth/change-email")
    public Map<String, Object> changeEmail(@RequestBody ChangeEmailReq req, HttpServletRequest http) {
        var s = auth.session(tokenOf(http));
        if (s == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        if (!rateLimit.allowPerDay("auth:chmail:" + ClientIp.of(http), 5)) {
            return Map.of("ok", false, "message", "今日换绑次数已达上限，请明天再试");
        }
        var r = auth.changeEmail(s.userId(), req.password(), req.email());
        return Map.of("ok", r.ok(), "message", r.message());
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginReq req, HttpServletRequest http,
                                     HttpServletResponse res) {
        String ip = ClientIp.of(http);
        if (!rateLimit.allowPerHour("auth:login:" + ip, 30)) {
            return Map.of("ok", false, "message", "尝试过于频繁，请稍后再试");
        }
        var r = auth.login(req.username(), req.password(), ip);
        return finishLogin(r, res);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(tokenOf(req));
        var cookie = new Cookie(COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        res.addCookie(cookie);
        return Map.of("ok", true);
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(HttpServletRequest req) {
        var s = auth.session(tokenOf(req));
        if (s == null) {
            return Map.of("ok", false);
        }
        var out = new HashMap<String, Object>();
        out.put("ok", true);
        out.put("username", s.username());
        out.put("role", s.role());
        out.put("emailVerified", s.emailVerified());
        out.put("email", s.email() == null ? "" : s.email());
        var u = auth.profile(s.userId());
        if (u != null) {
            out.put("createdAt", u.createdAt());
            out.put("lastLoginAt", u.lastLoginAt());
        }
        return out;
    }

    private Map<String, Object> finishLogin(UserAuthService.Result r, HttpServletResponse res) {
        if (!r.ok()) {
            return Map.of("ok", false, "message", r.message());
        }
        var cookie = new Cookie(COOKIE, r.token());
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        res.addCookie(cookie);
        var out = new HashMap<String, Object>();
        out.put("ok", true);
        out.put("token", r.token());
        out.put("username", r.user().username());
        out.put("role", r.user().role());
        return out;
    }

    // ── 管理端（/api/boards/** 由 AdminAuthFilter 保护）──────────────────────────

    public record InviteReq(String note) {}

    public record CodeReq(String code) {}

    public record UserActionReq(Long id, Boolean disabled) {}

    @PostMapping("/api/boards/invites/create")
    public Map<String, Object> createInvite(@RequestBody(required = false) InviteReq req,
                                            HttpServletRequest http) {
        String by = adminName(http);
        var inv = auth.createAdminInvite(req == null ? "" : req.note(), by);
        return Map.of("ok", true, "code", inv.code(), "expiresAt", inv.expiresAt());
    }

    @GetMapping("/api/boards/invites/list")
    public Map<String, Object> listInvites() {
        return Map.of("invites", auth.listInvites().stream().map(i -> {
            var m = new HashMap<String, Object>();
            m.put("code", i.code());
            m.put("role", i.role());
            m.put("note", i.note() == null ? "" : i.note());
            m.put("createdBy", i.createdBy() == null ? "" : i.createdBy());
            m.put("createdAt", i.createdAt());
            m.put("expiresAt", i.expiresAt());
            m.put("usedBy", i.usedBy() == null ? "" : i.usedBy());
            m.put("usedAt", i.usedAt());
            m.put("revoked", i.revoked());
            return m;
        }).toList());
    }

    @PostMapping("/api/boards/invites/revoke")
    public Map<String, Object> revokeInvite(@RequestBody CodeReq req) {
        boolean hit = req != null && req.code() != null && auth.revokeInvite(req.code());
        return hit ? Map.of("ok", true) : Map.of("ok", false, "message", "邀请码不存在");
    }

    @GetMapping("/api/boards/users/list")
    public Map<String, Object> listUsers() {
        return Map.of("users", auth.listUsers().stream().map(u -> {
            var m = new HashMap<String, Object>();
            m.put("id", u.id());
            m.put("username", u.username());
            m.put("role", u.role());
            m.put("invitedBy", u.invitedBy() == null ? "" : u.invitedBy());
            m.put("createdAt", u.createdAt());
            m.put("lastLoginAt", u.lastLoginAt());
            m.put("disabled", u.disabled());
            return m;
        }).toList());
    }

    @PostMapping("/api/boards/users/disable")
    public Map<String, Object> disableUser(@RequestBody UserActionReq req) {
        if (req == null || req.id() == null) {
            return Map.of("ok", false, "message", "缺少 id");
        }
        boolean hit = auth.setUserDisabled(req.id(), req.disabled() == null || req.disabled());
        return hit ? Map.of("ok", true) : Map.of("ok", false, "message", "用户不存在");
    }

    /** 邀请码签发人：管理员账号显示用户名；站长 TOTP 会话显示「站长」。 */
    private String adminName(HttpServletRequest req) {
        var s = auth.session(userTokenOf(req));
        return (s != null && UserAuthService.ROLE_ADMIN.equals(s.role())) ? s.username() : "站长";
    }

    static String tokenOf(HttpServletRequest req) {
        return userTokenOf(req);
    }

    static String userTokenOf(HttpServletRequest req) {
        String h = req.getHeader("X-User-Token");
        if (h != null && !h.isBlank()) {
            return h;
        }
        if (req.getCookies() != null) {
            for (var c : req.getCookies()) {
                if (COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

}
