package com.rankharvester.web;

import com.rankharvester.rank.store.MessageBoardStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 留言板 API。
 *
 * <ul>
 *   <li>{@code GET  /api/public/messages}        前台分页读可见留言（公开，不下发 IP）</li>
 *   <li>{@code POST /api/public/messages}        前台发留言（极验 + Redis 限流 + DuckDB 兜底限流）</li>
 *   <li>{@code GET  /api/boards/messages}        后台分页读全部留言（受 {@code /api/boards/**} 鉴权保护）</li>
 *   <li>{@code POST /api/boards/messages/action} 后台操作：hide/show/pin/unpin/reply/delete（受保护）</li>
 * </ul>
 */
@RestController
public class MessageBoardController {

    /** 前台每页条数。 */
    private static final int PAGE_SIZE = 20;
    /** 昵称最大长度。 */
    private static final int MAX_NICKNAME = 24;
    /** 留言最大长度（内容按 Markdown 原文存储，渲染端净化，故只限长度不限语法）。 */
    private static final int MAX_CONTENT = 2000;
    /** 站长回复最大长度（同样支持 Markdown）。 */
    private static final int MAX_REPLY = 2000;
    /** 单 IP 每天最多发几条（隐性规则，前台不展示）。 */
    private static final int IP_DAILY_LIMIT = 3;
    /** 全站每小时最多收几条（防分布式刷屏）。 */
    private static final int GLOBAL_HOURLY_LIMIT = 120;
    /** 邮箱格式（与活动订阅同级别的宽松校验）。 */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MessageBoardStore store;
    private final RateLimitService rateLimit;
    private final MessageModerationService moderation;
    private final MessageHousekeepingService housekeeping;
    private final UserAuthService userAuth;

    public MessageBoardController(MessageBoardStore store,
                                  RateLimitService rateLimit, MessageModerationService moderation,
                                  MessageHousekeepingService housekeeping, UserAuthService userAuth) {
        this.store = store;
        this.rateLimit = rateLimit;
        this.moderation = moderation;
        this.housekeeping = housekeeping;
        this.userAuth = userAuth;
    }

    /** 发留言请求体：登录用户只需填内容（昵称取用户名、邮箱取账号邮箱）。 */
    public record PostReq(String content) {}

    /** 后台操作请求体：action ∈ hide/show/pin/unpin/reply/delete；reply 时 text 为回复内容（空=清除）。 */
    public record ActionReq(Long id, String action, String text) {}

    /** 前台读：仅可见留言，置顶优先、新→旧；不下发 IP。 */
    @GetMapping("/api/public/messages")
    public Map<String, Object> publicList(@RequestParam(defaultValue = "1") int page) {
        var p = store.listPublic(Math.max(1, page), PAGE_SIZE);
        return Map.of(
                "total", p.total(),
                "page", Math.max(1, page),
                "size", PAGE_SIZE,
                "messages", p.messages().stream().map(m -> {
                    var out = new HashMap<String, Object>();
                    out.put("id", m.id());
                    out.put("nickname", m.nickname());
                    out.put("content", m.content());
                    out.put("createdAt", m.createdAt());
                    out.put("pinned", m.pinned());
                    out.put("reply", m.replyText() == null ? "" : m.replyText());
                    out.put("replyAt", m.replyAt());
                    out.put("mergedCount", m.mergedCount());
                    return out;
                }).toList());
    }

    /**
     * 前台发留言：<b>需登录且邮箱已验证</b>。昵称取用户名、邮箱取账号邮箱，前端只传内容。
     * 业务失败统一返回 {ok:false, message}（HTTP 200，与本站其它公开提交接口一致）。
     */
    @PostMapping("/api/public/messages")
    public Map<String, Object> publicPost(@RequestBody PostReq req, HttpServletRequest http) {
        var s = userAuth.session(UserAuthController.userTokenOf(http));
        if (s == null) {
            return fail("发留言需登录账号");
        }
        if (!s.emailVerified()) {
            return fail("请先完成邮箱验证后再发言（验证邮件已发到注册邮箱）");
        }
        String nickname = clean(s.username(), false);
        String email = s.email() == null ? "" : s.email().trim();
        String content = clean(req.content(), true);
        if (content.isEmpty()) {
            return fail("留言内容不能为空");
        }
        if (content.length() > MAX_CONTENT) {
            return fail("留言太长（最多 " + MAX_CONTENT + " 字）");
        }
        // 限流：单账号每天 IP_DAILY_LIMIT 条 + 全站小时窗，防刷屏。
        if (!rateLimit.allowPerDay("msg:u:" + s.userId(), IP_DAILY_LIMIT)) {
            return fail("今天的留言次数用完啦，明天再来～");
        }
        if (!rateLimit.allowPerHour("msg:all", GLOBAL_HOURLY_LIMIT)) {
            return fail("留言板今天有点挤，请稍后再试");
        }
        String ip = ClientIp.of(http);
        long id = store.add(nickname, content, ip, email, System.currentTimeMillis());
        // 先显示后审核：立即可见，AI 审核异步跑，不通过会下架并邮件告知原因
        moderation.reviewAsync(id, nickname, content, email);
        return Map.of("ok", true, "id", id, "message", "留言成功！");
    }

    /** 后台读：全部留言（含隐藏），带 IP。 */
    @GetMapping("/api/boards/messages")
    public Map<String, Object> adminList(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        int sz = Math.min(200, Math.max(1, size));
        var p = store.listAdmin(Math.max(1, page), sz);
        return Map.of(
                "total", p.total(),
                "page", Math.max(1, page),
                "size", sz,
                "messages", p.messages().stream().map(m -> {
                    var out = new HashMap<String, Object>();
                    out.put("id", m.id());
                    out.put("nickname", m.nickname());
                    out.put("content", m.content());
                    out.put("ip", m.ip() == null ? "" : m.ip());
                    out.put("email", m.email() == null ? "" : m.email());
                    out.put("createdAt", m.createdAt());
                    out.put("visible", m.visible());
                    out.put("pinned", m.pinned());
                    out.put("reply", m.replyText() == null ? "" : m.replyText());
                    out.put("replyAt", m.replyAt());
                    out.put("reviewStatus", m.reviewStatus() == null ? "" : m.reviewStatus());
                    out.put("reviewReason", m.reviewReason() == null ? "" : m.reviewReason());
                    out.put("mergedCount", m.mergedCount());
                    return out;
                }).toList());
    }

    /** 后台手动触发一轮 AI 刷屏整理（平时每小时自动跑；此入口便于即时治理与验证）。 */
    @PostMapping("/api/boards/messages/housekeep")
    public Map<String, Object> adminHousekeep() {
        return Map.of("ok", true, "message", housekeeping.runOnce());
    }

    /** 后台操作。 */
    @PostMapping("/api/boards/messages/action")
    public Map<String, Object> adminAction(@RequestBody ActionReq req) {
        if (req.id() == null || req.action() == null) {
            return fail("缺少 id 或 action");
        }
        long id = req.id();
        boolean hit;
        switch (req.action()) {
            case "hide" -> hit = store.setVisible(id, false);
            case "show" -> hit = store.setVisible(id, true);
            case "pin" -> hit = store.setPinned(id, true);
            case "unpin" -> hit = store.setPinned(id, false);
            case "delete" -> hit = store.delete(id);
            case "reply" -> {
                String text = clean(req.text(), true);
                if (text.length() > MAX_REPLY) {
                    return fail("回复太长（最多 " + MAX_REPLY + " 字）");
                }
                hit = store.reply(id, text, System.currentTimeMillis());
            }
            default -> {
                return fail("未知操作: " + req.action());
            }
        }
        return hit ? Map.of("ok", true) : fail("留言不存在（可能已被删除）");
    }

    private static Map<String, Object> fail(String message) {
        return Map.of("ok", false, "message", message);
    }

    /**
     * 文本清洗：统一换行、去首尾空白、剔除控制字符（防注入怪字符撑爆排版）。
     * allowNewline=false 时换行也一并剔除（昵称单行）。
     */
    private static String clean(String s, boolean allowNewline) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        var sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') {
                if (allowNewline) {
                    sb.append(c);
                }
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        // 连续 3+ 空行压成 2 行，防刷屏拉长页面
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

}
