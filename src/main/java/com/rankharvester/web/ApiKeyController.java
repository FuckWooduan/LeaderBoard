package com.rankharvester.web;

import com.rankharvester.apikey.ApiKeyService;
import com.rankharvester.apikey.ApiKeyStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API key：
 * <ul>
 *   <li><b>后台管理</b>（{@code /api/boards/apikey/**}，受 {@link AdminAuthFilter} 保护）：
 *       创建（带备注）/ 列出 / 启用停用（失效）/ 删除。</li>
 *   <li><b>用户自助</b>（{@code /api/user/apikey}，需站点账号登录）：每个登录用户可生成/重置
 *       <b>一个</b>属于自己的 key，用它程序化调用下面的 key 通道接口（等同登录态 + 免人机验证）。</li>
 *   <li><b>对外 key 通道</b>（{@code /api/key/**}）：请求头 {@code X-API-Key}（或
 *       {@code Authorization: Bearer <key>}）带有效 key 即可调用，无效 key 401。
 *       覆盖原本需要登录/人机验证的查询：玩家在线查询、在线位置（频道/房间）。</li>
 * </ul>
 */
@RestController
public class ApiKeyController {

    private final ApiKeyService keys;
    private final PublicRankController publicRank;
    private final VisitStatsService stats;
    private final UserAuthService userAuth;

    public ApiKeyController(ApiKeyService keys, PublicRankController publicRank, VisitStatsService stats,
            UserAuthService userAuth) {
        this.keys = keys;
        this.publicRank = publicRank;
        this.stats = stats;
        this.userAuth = userAuth;
    }

    // ───────────── 后台管理（受 AdminAuthFilter 保护）─────────────

    @GetMapping("/api/boards/apikey/list")
    public Object list() {
        return Map.of("keys", keys.list());
    }

    @PostMapping("/api/boards/apikey/create")
    public Object create(@RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? "" : String.valueOf(body.getOrDefault("note", "")).trim();
        ApiKeyStore.ApiKey k = keys.create(note);
        return Map.of("ok", true, "id", k.id(), "key", k.key(), "note", k.note());
    }

    @PostMapping("/api/boards/apikey/active")
    public Object setActive(@RequestBody Map<String, Object> body) {
        long id = num(body.get("id"));
        boolean active = Boolean.TRUE.equals(body.get("active"))
                || "true".equals(String.valueOf(body.get("active")));
        keys.setActive(id, active);
        return Map.of("ok", true);
    }

    @PostMapping("/api/boards/apikey/delete")
    public Object delete(@RequestBody Map<String, Object> body) {
        keys.delete(num(body.get("id")));
        return Map.of("ok", true);
    }

    // ───────────── 用户自助（需站点账号登录）─────────────

    /** 查看自己的 API key（不存在返回 {exists:false}）。 */
    @GetMapping("/api/user/apikey")
    public Object myKey(HttpServletRequest req) {
        var s = requireUser(req);
        ApiKeyStore.ApiKey k = keys.findByUser(s.userId());
        if (k == null) {
            return Map.of("exists", false);
        }
        return Map.of("exists", true, "key", k.key(), "active", k.active(),
                "createdAt", k.createdAt(), "callCount", keys.liveCallCount(k));
    }

    /** 生成/重置自己的 API key（每用户一个；重置后旧 key 立即失效）。 */
    @PostMapping("/api/user/apikey/reset")
    public Object resetMyKey(HttpServletRequest req) {
        var s = requireUser(req);
        ApiKeyStore.ApiKey k = keys.resetForUser(s.userId(), s.username());
        return Map.of("ok", true, "key", k.key(), "createdAt", k.createdAt());
    }

    private com.rankharvester.rank.store.UserStore.SessionUser requireUser(HttpServletRequest req) {
        var s = userAuth.session(UserAuthController.userTokenOf(req));
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需登录站点账号");
        }
        return s;
    }

    // ───────────── 对外：key 鉴权通道（等同登录态 + 免人机验证）─────────────

    /**
     * 查玩家在线情况。鉴权：请求头 {@code X-API-Key} 或 {@code Authorization: Bearer <key>}。
     * 入参：{@code name}（角色名）、{@code server}（对外 districtId）。
     */
    @GetMapping("/api/key/player")
    public Object player(HttpServletRequest req, @RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String server) {
        requireKey(req);
        return publicRank.playerLookup(name, server);
    }

    /**
     * 查在线玩家位置（频道/房间）。鉴权同上。
     * 入参：{@code server}（对外 districtId）、{@code charId}（来自 /api/key/player 的 characterId）。
     */
    @GetMapping("/api/key/player/location")
    public Object playerLocation(HttpServletRequest req, @RequestParam String server, @RequestParam long charId) {
        requireKey(req);
        return publicRank.locationLookup(server, charId);
    }

    private void requireKey(HttpServletRequest req) {
        ApiKeyStore.ApiKey k = keys.validate(extractKey(req));
        if (k == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效或已停用的 API key");
        }
        // 用户自助 key：归属账号被停用则一并失效（后台停用账号即时切断其 key 通道）
        if (k.userId() > 0 && !userAuth.isUserActive(k.userId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "该 API key 所属账号已停用");
        }
        stats.incrInternalApi(); // API key 调用计入「内部」接口调用数
    }

    /**
     * 从 {@code X-API-Key} 头或 {@code Authorization: Bearer} 取 key。
     * <b>不</b>支持 {@code ?key=} 查询参数：URL 会进访问日志/Referer/历史，易泄露。
     */
    private static String extractKey(HttpServletRequest req) {
        String k = req.getHeader("X-API-Key");
        if (k != null && !k.isBlank()) {
            return k.trim();
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
