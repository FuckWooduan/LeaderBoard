package com.rankharvester.web;

import com.rankharvester.net.GameServer;
import com.rankharvester.net.PlayerSearchResult;
import com.rankharvester.net.PlayerSearchService;
import com.rankharvester.rank.admin.PublicRankService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 前端公开读 API（无需鉴权，仅暴露已开放的榜）。
 *
 * <ul>
 *   <li>{@code GET /api/public/boards}            已开放的榜列表</li>
 *   <li>{@code GET /api/public/board?code=&page=} 某榜分页（每页 100，含字段显示名）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/public")
public class PublicRankController {

    /** 单省每小时玩家查询上限。 */
    private static final int PLAYER_PROVINCE_HOURLY_LIMIT = 10;

    private final PublicRankService service;
    private final PlayerSearchService playerSearch;
    private final GeoService geo;
    private final RateLimitService rateLimit;
    private final CaptchaService captcha;
    private final UserAuthService userAuth;
    private final com.rankharvester.rank.sub.PlayerChangeService playerChangeService;
    private final com.rankharvester.rank.queue.RedisTaskQueue taskQueue;
    private final com.rankharvester.rank.store.LeaderboardCatalog catalog;
    /** 强制刷新冷却：每个榜 code 上次强制刷新时间，防止狂点刷爆游戏服/代理。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> forceRefreshAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FORCE_REFRESH_COOLDOWN_MS = 20_000L;

    public PublicRankController(
            PublicRankService service, PlayerSearchService playerSearch, GeoService geo, RateLimitService rateLimit,
            CaptchaService captcha, UserAuthService userAuth,
            com.rankharvester.rank.sub.PlayerChangeService playerChangeService,
            com.rankharvester.rank.queue.RedisTaskQueue taskQueue,
            com.rankharvester.rank.store.LeaderboardCatalog catalog) {
        this.service = service;
        this.playerSearch = playerSearch;
        this.geo = geo;
        this.rateLimit = rateLimit;
        this.captcha = captcha;
        this.userAuth = userAuth;
        this.playerChangeService = playerChangeService;
        this.taskQueue = taskQueue;
        this.catalog = catalog;
    }

    /**
     * 强制刷新某个榜：把该榜立刻入队（dueAt=now），下个抓取 tick（~2s）即抓。
     * 单服榜=刷新该区；跨服榜（code 末段 all）本就是一个榜=刷新全部。带 20s 冷却防刷。
     * 入参：{@code {code}}（来自榜数据响应里的 code 字段）。
     */
    @PostMapping("/board/refresh")
    public Map<String, Object> refreshBoard(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String code = str(body.get("code"));
        if (code.isBlank()) {
            return Map.of("ok", false, "message", "缺少榜 code");
        }
        // 强制刷新吃后端资源，需过人机验证
        if (!captcha.verify(str(body.get("cfToken")), str(body.get("gtPayload")))) {
            return Map.of("ok", false, "message", "请完成人机验证后再刷新");
        }
        long now = System.currentTimeMillis();
        Long last = forceRefreshAt.get(code);
        if (last != null && now - last < FORCE_REFRESH_COOLDOWN_MS) {
            long wait = (FORCE_REFRESH_COOLDOWN_MS - (now - last)) / 1000 + 1;
            return Map.of("ok", false, "message", "刚刷新过，请 " + wait + " 秒后再试", "cooldown", true);
        }

        // 战队全服榜（TEAM:all）是聚合视图、不是单个 catalog 榜：展开为所有 TEAM:<区> 一起入队。
        if ("TEAM:all".equals(code)) {
            var teamDefs = catalog.all().stream().filter(d -> d.code().startsWith("TEAM:")).toList();
            if (teamDefs.isEmpty()) {
                return Map.of("ok", false, "message", "暂无可刷新的战队榜");
            }
            forceRefreshAt.put(code, now);
            for (var d : teamDefs) {
                taskQueue.enqueue(d.code() + "#force" + (now / FORCE_REFRESH_COOLDOWN_MS), d.code(), d.kind().lane(), now);
            }
            return Map.of("ok", true, "message", "已加入刷新队列（全服战队榜共 " + teamDefs.size() + " 个区），稍后更新");
        }

        var def = catalog.byCode(code).orElse(null);
        if (def == null) {
            return Map.of("ok", false, "message", "未知榜: " + code);
        }
        forceRefreshAt.put(code, now);
        // 固定 taskId（按 20s 桶）→ 短时间内多次点击幂等为一个任务；dueAt=now 立刻到期。
        taskQueue.enqueue(code + "#force" + (now / FORCE_REFRESH_COOLDOWN_MS), code, def.kind().lane(), now);
        return Map.of("ok", true, "message", "已加入刷新队列，约 10 秒后更新（大榜稍久）");
    }

    @GetMapping("/boards")
    public List<PublicRankService.BoardBrief> boards() {
        return service.publicBoards();
    }

    @GetMapping("/board")
    public PublicRankService.RankPage board(@RequestParam String code, @RequestParam(defaultValue = "1") int page) {
        try {
            return service.page(code, page);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    /** 某标签可选区服（power/exp/ladder）；返回 id(districtId) + name(区服名)。 */
    @GetMapping("/servers")
    public List<PublicRankService.ServerOption> servers(@RequestParam String tab) {
        return service.serversForTab(tab);
    }

    /** 标签分页：tab=power|exp|ladder，server 空=全部/跨服。 */
    @GetMapping("/tab")
    public PublicRankService.RankPage tab(
            @RequestParam String tab,
            @RequestParam(required = false, defaultValue = "") String server,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer vip) {
        try {
            if ("team".equals(tab)) {
                return service.teamPage(server, page, q, sort);
            }
            return service.pageByTab(tab, server, page, q, vip);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    /** 单个登录用户每小时玩家查询上限（含 /player 与 /player/location，防滥用游戏服长连接）。 */
    private static final int PLAYER_QUERY_HOURLY_LIMIT = 120;

    /**
     * 玩家查询：登录 → searchCharacterByName，<b>拿到在线状态即返回</b>（含<b>跨服</b>战力/经验名次）。
     * 频道/房间由前端拿到 online 后再调 {@link #playerLocation} 异步补查。
     *
     * <p><b>需站点账号登录</b>（登录态免人机验证）；按账号每小时限流防滥用；程序化调用走 API Key 通道 {@code /api/key/player}。
     */
    @GetMapping("/player")
    public Map<String, Object> player(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String server,
            HttpServletRequest req) {
        requirePlayerAccess(req);
        return playerLookup(name, server);
    }

    /** 玩家查询鉴权 + 限流：需登录且未超账号每小时配额（程序化调用走 API Key 通道）。 */
    private void requirePlayerAccess(HttpServletRequest req) {
        var s = userAuth.session(UserAuthController.userTokenOf(req));
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "玩家在线查询需登录账号");
        }
        if (!rateLimit.allowPerHour("player:u:" + s.userId(), PLAYER_QUERY_HOURLY_LIMIT)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "查询过于频繁，请稍后再试");
        }
    }

    /**
     * 玩家查询核心（不含人机验证，供登录校验通过后复用）：登录搜索 + 跨服战力/经验名次。
     * server=对外 districtId。
     */
    public Map<String, Object> playerLookup(String name, String server) {
        // server 为对外 districtId → 登录 id
        String loginIdStr = GameServer.loginIdOfDistrict(server);
        if (loginIdStr == null) {
            return Map.of("available", false, "name", name, "server", server, "message", "未知区服: " + server);
        }
        try {
            var c = playerSearch.searchCharacter(name, Integer.parseInt(loginIdStr));
            var out = new java.util.HashMap<String, Object>();
            out.put("available", true);
            out.put("name", c.characterName());
            out.put("online", c.isOnline());
            out.put("charName", c.characterName());
            out.put("characterId", c.characterId()); // 供前端异步查频道/房间
            out.put("teamName", c.teamName());
            out.put("lv", c.lv());
            out.put("server", server);
            out.put("serverName", GameServer.nameOfDistrict(parseIntSafe(server)));
            out.put("logoutTime", c.logoutTime());
            // 跨服 战力榜 / 经验榜 真实名次 + 数值（按精确角色名查；不在榜则缺省）
            addCrossRank(out, "power", c.characterName());
            addCrossRank(out, "exp", c.characterName());
            return out; // 不含 channel/room —— 前端随后调 /player/location 异步补查
        } catch (PlayerSearchService.PlayerNotFoundException e) {
            // 查无此人 → 不报错；返回 notFound，前端改用跨服战力模糊搜索给出「你可能想找?」
            return Map.of("available", true, "notFound", true, "name", name, "server", server);
        } catch (RuntimeException e) {
            // 在线状态查询失败/超时 → 退而展示该玩家在跨服榜单的战力/经验
            var out = new java.util.HashMap<String, Object>();
            boolean hasRank = addCrossRank(out, "power", name) | addCrossRank(out, "exp", name);
            if (hasRank) {
                out.put("available", true);
                out.put("name", name);
                out.put("charName", name);
                out.put("queryFailed", true);
                out.put("server", server);
                out.put("serverName", GameServer.nameOfDistrict(parseIntSafe(server)));
                out.put("message", "在线状态查询超时，以下为跨服榜单数据");
                return out;
            }
            return Map.of("available", false, "name", name, "server", server,
                    "message", "查询失败：" + e.getMessage());
        }
    }

    /** 异步补查在线玩家的位置（频道/房间）；server=districtId，charId 来自 /player 返回的 characterId。需登录。 */
    @GetMapping("/player/location")
    public Map<String, Object> playerLocation(@RequestParam String server, @RequestParam long charId,
            HttpServletRequest req) {
        requirePlayerAccess(req);
        return locationLookup(server, charId);
    }

    /** 位置查询核心（不含鉴权，供登录校验通过后 / 内部 API 复用）。 */
    public Map<String, Object> locationLookup(String server, long charId) {
        String loginIdStr = GameServer.loginIdOfDistrict(server);
        if (loginIdStr == null) {
            return Map.of("available", false, "message", "未知区服");
        }
        try {
            var loc = playerSearch.locationOf(Integer.parseInt(loginIdStr), charId);
            return Map.of("available", true,
                    "inGame", loc.inGame(),
                    "channel", loc.channelName() == null ? "—" : loc.channelName(),
                    "room", loc.roomId());
        } catch (RuntimeException e) {
            return Map.of("available", false, "message", "频道信息查询失败");
        }
    }

    /**
     * 订阅某玩家的变化通知（改名 / VIP 提升 / 境界提升），变化时邮件通知。
     * 入参：{@code {name, server(districtId), email, rename, vip, realm}}。按 GCID 订阅，检测源为同服战力榜。
     */
    @PostMapping("/player/subscribe")
    public Map<String, Object> playerSubscribe(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String server = str(body.get("server"));
        String email = str(body.get("email"));
        if (name.isBlank() || email.isBlank() || !email.contains("@")) {
            return Map.of("ok", false, "message", "请填写有效的邮箱与玩家名");
        }
        String loginIdStr = GameServer.loginIdOfDistrict(server);
        if (loginIdStr == null) {
            return Map.of("ok", false, "message", "未知区服: " + server);
        }
        boolean rename = truthy(body.get("rename"));
        boolean vip = truthy(body.get("vip"));
        boolean realm = truthy(body.get("realm"));
        var r = playerChangeService.subscribe(email.trim(), loginIdStr, name, rename, vip, realm);
        var out = new java.util.HashMap<String, Object>();
        out.put("ok", r.ok());
        out.put("message", r.message());
        if (r.gcId() != null) {
            out.put("gcId", r.gcId());
        }
        return out;
    }

    /** 退订：{@code {email, gcId}}。 */
    @PostMapping("/player/unsubscribe")
    public Map<String, Object> playerUnsubscribe(@RequestBody Map<String, Object> body) {
        String email = str(body.get("email"));
        long gcId = body.get("gcId") instanceof Number n ? n.longValue() : parseLongSafe(str(body.get("gcId")));
        if (email.isBlank() || gcId == 0) {
            return Map.of("ok", false, "message", "缺少邮箱或 gcId");
        }
        playerChangeService.unsubscribe(email.trim(), gcId);
        return Map.of("ok", true, "message", "已退订");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 查某玩家在<b>跨服</b>榜（power→REDUCED:333 / exp→REDUCED:332 canonical）的名次，放进 out[tab]。返回是否命中。 */
    private boolean addCrossRank(Map<String, Object> out, String tab, String charName) {
        try {
            var r = service.playerRankIn(service.resolveCode(tab, ""), charName);
            if (r != null) {
                out.put(tab, Map.of("rank", r.rank(), "value", r.score()));
                return true;
            }
        } catch (RuntimeException ignore) {
            // 跨服榜暂无数据 → 缺省
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
