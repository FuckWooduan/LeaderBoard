package com.rankharvester.net;

import static com.rankharvester.apc.ApcValues.asIntOr;

import com.rankharvester.account.GameAccount;
import com.rankharvester.account.ScoutAccountRegistry;
import com.rankharvester.account.ScoutAccountStore;
import com.rankharvester.account.ScoutLevelStore;
import com.rankharvester.apc.ApcObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 侦察连接层（Tier 1 · 大厅）：用 {@link ScoutAccountRegistry} 里的<strong>侦察号</strong>登录大厅，
 * 维持常热连接发大厅级查询（频道列表/在线人数）、并为房间提供「按等级选号 → 进频道」的授权与 token。
 *
 * <p><b>方案 B「懒登 + 缓存等级」</b>：号的等级<strong>只在第一次需要时登一次问出来</strong>（顺手抓 token+lv），
 * 写进持久缓存 {@link ScoutLevelStore}（重启不丢）；配置直接填了 {@code level} 则连这次懒登都省。
 * 故 20区×6号也<strong>不会全连</strong>——连接数 = 实际被看的(区,频道)数，与号总数无关。
 *
 * <p>连接<strong>按号</strong>（accountId）维护：每个号自己的大厅连接带自己的 token+level；进频道用「能进的那个号」。
 * 与抓榜/玩家查询完全隔离（只用侦察号、独立连接池/锁）。
 */
@Service
public class ScoutConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ScoutConnectionService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final long IDLE_EVICT_MS = 15 * 60_000L;
    /** 单条侦察长连接<strong>硬性最大存活</strong>：到点强制重连（无视是否活跃），珍惜节点、避免长期占用同一出口。 */
    private static final long MAX_CONN_AGE_MS = 10 * 60_000L;

    private final GameClient gameClient;
    private final ScoutAccountRegistry scouts;
    private final ScoutLevelStore levelStore;
    private final ScoutAccountStore scoutStore;
    private final PandaProxyService panda;

    /** (号,区) → 该号在该区的常热大厅连接。key = accountId@district。 */
    private final ConcurrentHashMap<String, GameConnection> hallPool = new ConcurrentHashMap<>();
    /** (号,区) → 一把锁（同连接同时刻只允许一个进行中的 APC 请求）。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** (号,区) → 最近使用时间（闲置回收用）。 */
    private final ConcurrentHashMap<String, Long> lastUsedAt = new ConcurrentHashMap<>();
    /** (号,区) → 该连接建立时间（10 分钟硬 TTL 用）。 */
    private final ConcurrentHashMap<String, Long> createdAt = new ConcurrentHashMap<>();
    /** (号,区) → 该区大厅登录 token（enterChannel 用）。 */
    private final ConcurrentHashMap<String, String> hallToken = new ConcurrentHashMap<>();
    /** districtId → 最近一次原始频道列表缓存（供 channelEndpoint 复用，避免轮巡反复全量拉）。 */
    private final ConcurrentHashMap<String, RawCache> rawChannelCache = new ConcurrentHashMap<>();
    private static final long RAW_CACHE_TTL_MS = 10_000L;

    private record RawCache(List<Map<String, Object>> list, long ts) {}

    public ScoutConnectionService(GameClient gameClient, ScoutAccountRegistry scouts, ScoutLevelStore levelStore,
            ScoutAccountStore scoutStore, PandaProxyService panda) {
        this.gameClient = gameClient;
        this.scouts = scouts;
        this.levelStore = levelStore;
        this.scoutStore = scoutStore;
        this.panda = panda;
    }

    @PostConstruct
    void seedConfigLevels() {
        if (scouts != null && levelStore != null) {
            // 配置/后台手填了 level → 预置该号每个区的缓存，免懒登
            scouts.configLevelSeeds().forEach(seed -> {
                for (String district : seed.districts()) {
                    levelStore.put(seed.accountId(), district, seed.level());
                }
            });
        }
    }

    /** 该区是否配了侦察号（没配则频道/房间该区不可用）。 */
    public boolean available(String district) {
        return scouts.has(district);
    }

    /** 所有配了侦察号的区（对外区号）。 */
    public List<String> availableDistricts() {
        return scouts.districts();
    }

    /** 某区频道列表（含在线人数）：用该区<strong>任一可用号</strong>的大厅连接 {@code getRoomChannel}（只发一个包，换号兜底）。 */
    public List<Map<String, Object>> channelList(String district) {
        var list = onAnyHall(district, conn -> firstList(conn.call("getRoomChannel", "callChannelList", CALL_TIMEOUT)));
        rawChannelCache.put(district, new RawCache(list, System.currentTimeMillis())); // 顺手缓存原始列表，供 channelEndpoint 复用
        return list;
    }

    /** 某区某频道的频道服 host:port：<strong>优先复用近期缓存的频道列表</strong>，过期才重拉（避免轮巡时反复全量拉）。 */
    public String[] channelEndpoint(String district, int channelId) {
        RawCache rc = rawChannelCache.get(district);
        List<Map<String, Object>> list;
        if (rc != null && System.currentTimeMillis() - rc.ts() < RAW_CACHE_TTL_MS) {
            list = rc.list();
        } else {
            list = channelList(district); // 会刷新缓存
        }
        for (var c : list) {
            if (asIntOr(c.get("id"), 0) == channelId) {
                String host = firstNonBlank(c.get("serverDomain"), c.get("ip"));
                int port = asIntOr(c.get("port"), 0);
                return (host != null && port > 0) ? new String[]{host, String.valueOf(port)} : null;
            }
        }
        return null;
    }

    /**
     * 在该区<strong>任一可用号</strong>的大厅连接上执行 task（大厅级查询，任号结果相同，只用一个号）：
     * 优先用已登录的号；否则按池顺序逐个尝试，<strong>第一个成功的</strong>即用；全失败才抛错。
     * 故「池里有一个号能登」就拉得到，且只发一个包、不会每个号都发。
     */
    private <T> T onAnyHall(String district, ConnTask<T> task) {
        var pool = scouts.forDistrict(district);
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("该区未配置侦察号: " + district);
        }
        // 优先该区已登录的号（已有该区大厅连接）
        var ordered = new ArrayList<GameAccount>();
        for (GameAccount a : pool) {
            if (hallPool.containsKey(hallKey(a.id(), district))) ordered.add(a);
        }
        for (GameAccount a : pool) {
            if (!hallPool.containsKey(hallKey(a.id(), district))) ordered.add(a);
        }
        RuntimeException last = null;
        for (GameAccount a : ordered) {
            try {
                return onHall(a, district, task);
            } catch (PlayerSearchService.PlayerNotFoundException e) {
                throw e; // 查无此人：连接正常，不必换号
            } catch (RuntimeException e) {
                last = e;
                log.info("[scout {}] 号 {} 大厅查询失败，换下一个号: {}", district, a.id(), e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("该区无可用侦察号: " + district);
    }

    /**
     * 在该区<strong>任一可用侦察号</strong>的常热大厅连接上执行查询（供玩家查询复用侦察号长连接提速）。
     * 内部自带 (号,区) 锁、连接复用、失败重登重试，与频道大厅共享同一条连接。
     */
    public <T> T runOnHall(String district, java.util.function.Function<GameConnection, T> task) {
        return onAnyHall(district, task::apply);
    }

    /**
     * 某号在某区的等级（方案 B）：先查持久缓存；未知则<strong>懒登该区一次</strong>抓 lv（顺手缓存+持久化+回写表）。
     * 登录该区 loginId、抓该区角色等级（一号多区每区独立）。返回 <strong>-1=仍未知</strong>，0 及以上为真实等级。
     */
    public int levelOf(GameAccount account, String district) {
        if (levelStore.known(account.id(), district)) {
            return levelStore.get(account.id(), district);
        }
        try {
            onHall(account, district, conn -> null); // 触发该区登录 + capture（按区写 levelStore）
        } catch (RuntimeException e) {
            log.info("[scout {}] 懒登抓等级失败: {}", account.id(), e.getMessage());
        }
        return levelStore.known(account.id(), district) ? levelStore.get(account.id(), district) : -1;
    }

    /** 某号某区<strong>已知</strong>等级（只查缓存，不触发登录）。<strong>-1=未知</strong>（0 是合法等级）。 */
    public int knownLevel(GameAccount account, String district) {
        return levelStore.known(account.id(), district) ? levelStore.get(account.id(), district) : -1;
    }

    /** 忘记某号某区的等级缓存（进频道被拒→自愈：下次懒登重抓新等级）。 */
    public void forgetLevel(GameAccount account, String district) {
        levelStore.forget(account.id(), district);
    }

    /** 记录某 (号,区) 等级：写持久缓存 + 回写 scout_account.probed_levels（让后台可见真实等级）。 */
    void recordLevel(String accountId, String district, int level) {
        levelStore.put(accountId, district, level);
        Long rowId = scoutRowId(accountId);
        if (rowId != null) {
            scoutStore.putProbedLevel(rowId, district, level); // best-effort 回写，失败不影响调度
        }
    }

    /** accountId "scout-{id}" → 数字行 id；非此格式返回 null。 */
    private static Long scoutRowId(String accountId) {
        if (accountId == null || !accountId.startsWith("scout-")) {
            return null;
        }
        try {
            return Long.parseLong(accountId.substring("scout-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 全区探测用：拿该 pauth 登录指定区一次（独立短连，不入常热池），成功=该区有角色。
     * 返回该区角色等级（≥0），登录失败（无角色/被拒）返回 -1。顺手把等级写缓存+回写表。
     */
    public int probeRegion(GameAccount account, String loginId) {
        String district = GameServer.districtOfLoginId(loginId);
        GameConnection conn = null;
        try {
            ProxyEndpoint proxy = (panda != null) ? panda.acquire() : null; // 走熊猫动态 IP 分散；未启用=null 回退
            conn = gameClient.connectAndLogin(account, loginId, proxy);
            int lv = conn.loginLevel();
            if (lv < 0) {
                lv = 0;
            }
            if (district != null) {
                recordLevel(account.id(), district, lv);
            }
            return lv;
        } catch (RuntimeException e) {
            return -1; // 该区无角色 / 登录被拒
        } finally {
            closeQuietly(conn);
        }
    }

    /** 大厅侧授权某频道（用该号<strong>该区</strong>的连接）：{@code channelAvaliable → onChannelAvaliable[channelId, res]}。res=1 成功。 */
    public int authorizeChannel(GameAccount account, String district, int channelId) {
        return onHall(account, district, hall -> {
            ApcObject avail = hall.call("channelAvaliable", "onChannelAvaliable", CALL_TIMEOUT, channelId);
            return asIntOr(avail.param(1), 0); // [channelId, res]
        });
    }

    /** 某号<strong>该区</strong>的大厅 token（enterChannel 用，必须同区）；未登录则先触发该区一次登录捕获。 */
    public String hallToken(GameAccount account, String district) {
        onHall(account, district, hall -> null);
        return hallToken.get(hallKey(account.id(), district));
    }

    /** 裸连某频道服（委托 GameClient.connectRaw）。 */
    public GameConnection connectChannelServer(String label, String host, int port) {
        return gameClient.connectRaw(label, host, port);
    }

    /** 某区侦察号列表（供选号）。 */
    public List<GameAccount> scoutsOf(String district) {
        return scouts.forDistrict(district);
    }

    private static String firstNonBlank(Object a, Object b) {
        if (a != null && !a.toString().isBlank()) return a.toString();
        if (b != null && !b.toString().isBlank()) return b.toString();
        return null;
    }

    @FunctionalInterface
    private interface ConnTask<T> {
        T run(GameConnection conn);
    }

    /** 大厅连接复合键：一号多区，每个区独立登录（登该区 loginId、拿该区 token）。 */
    private static String hallKey(String accountId, String district) {
        return accountId + "@" + district;
    }

    /** 占该(号,区)锁 + 该区常热连接复用 + 失败重登重试一次，在该号<strong>该区</strong>大厅连接上执行 task。 */
    private <T> T onHall(GameAccount account, String district, ConnTask<T> task) {
        String key = hallKey(account.id(), district);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
        lock.lock();
        try {
            try {
                return task.run(acquireHall(account, district));
            } catch (PlayerSearchService.PlayerNotFoundException e) {
                throw e; // 查无此人：连接正常，不重登
            } catch (RuntimeException e) {
                log.info("[scout {}@{}] 复用大厅连接失败（{}），重登重试一次", account.id(), district, e.getMessage());
                evict(key);
                return task.run(acquireHall(account, district));
            } finally {
                lastUsedAt.put(key, System.currentTimeMillis());
            }
        } finally {
            lock.unlock();
        }
    }

    /** 取该号<strong>该区</strong>常热大厅连接：存活复用，否则登该区一条入池。调用方已持该(号,区)锁。 */
    private GameConnection acquireHall(GameAccount account, String district) {
        String key = hallKey(account.id(), district);
        GameConnection conn = hallPool.get(key);
        if (conn != null && conn.isActive() && !tooOld(key)) {
            return conn;
        }
        if (conn != null) {
            evict(key); // 失活或超 10 分钟硬 TTL：关闭并清 token/createdAt，下面重登
        }
        String loginId = GameServer.loginIdOfDistrict(district); // 登该区，不再用 servers[0]
        if (loginId == null) {
            throw new IllegalStateException("未知对外区号: " + district);
        }
        // 每区最多 1 条常热侦察连接：建新连接前挤掉本区其它号的连接（best-effort，不打断正在进行的查询）
        evictOtherInDistrict(district, key);
        GameConnection fresh = gameClient.connectAndLogin(account, loginId);
        hallPool.put(key, fresh);
        createdAt.put(key, System.currentTimeMillis());
        log.info("[scout {}@{}] 大厅登录成功（loginId={}）", account.id(), district, loginId);
        capture(account, district, fresh);
        return fresh;
    }

    /** 该 (号,区) 连接是否已超 10 分钟硬 TTL。 */
    private boolean tooOld(String key) {
        Long c = createdAt.get(key);
        return c != null && System.currentTimeMillis() - c > MAX_CONN_AGE_MS;
    }

    /** 每区 1 条上限：挤掉同区其它号的常热连接（抢不到锁=对方正在用，跳过，避免打断查询）。 */
    private void evictOtherInDistrict(String district, String keepKey) {
        String suffix = "@" + district;
        for (String k : new ArrayList<>(hallPool.keySet())) {
            if (k.equals(keepKey) || !k.endsWith(suffix)) {
                continue;
            }
            ReentrantLock lk = locks.get(k);
            if (lk != null && lk.tryLock()) {
                try {
                    evict(k);
                    lastUsedAt.remove(k);
                    log.info("[scout @{}] 每区限 1 条：挤掉旧号连接 {}", district, k);
                } finally {
                    lk.unlock();
                }
            }
        }
    }

    /**
     * 从握手连接顺手取 token + 等级，按 (号,区) 缓存（等级落持久化 + 回写 scout_account.probed_levels）。
     * dry-run 走 getUserDataByLogin 兜底。{@code district} = 本次登录的区，token/等级均归属该区。
     */
    private void capture(GameAccount account, String district, GameConnection conn) {
        String id = account.id();
        String key = hallKey(id, district);
        if (conn.loginLevel() > 0) {
            recordLevel(id, district, conn.loginLevel());
        }
        String token = conn.loginToken();
        if (token != null && !token.isBlank()) {
            hallToken.put(key, token);
            return;
        }
        try {
            ApcObject ud = conn.call("getUserDataByLogin", "callBackGetUserDataByLogin", CALL_TIMEOUT);
            if (ud.param(0) instanceof Map<?, ?> m) {
                if (m.get("lv") instanceof Number n) {
                    recordLevel(id, district, n.intValue());
                }
                if (m.get("token") != null && !m.get("token").toString().isBlank()) {
                    hallToken.put(key, m.get("token").toString());
                    return;
                }
            }
        } catch (RuntimeException ignore) {
            // best-effort
        }
        log.warn("[scout {}@{}] 未捕获到大厅 token（房间功能将不可用）", id, district);
    }

    /** 关闭并移除某 (号,区) 的大厅连接/token（key = accountId@district）。 */
    private void evict(String key) {
        GameConnection c = hallPool.remove(key);
        hallToken.remove(key);
        createdAt.remove(key);
        if (c != null) {
            closeQuietly(c);
        }
    }

    /** 回收：闲置过久 <strong>或</strong> 超 10 分钟硬 TTL 的常热侦察连接（按 (号,区) 键）。每分钟扫一次以兜住 TTL。 */
    @Scheduled(fixedDelay = 60_000L)
    void evictStale() {
        long now = System.currentTimeMillis();
        for (String key : new ArrayList<>(hallPool.keySet())) {
            Long lu = lastUsedAt.get(key);
            boolean idle = lu != null && now - lu >= IDLE_EVICT_MS;
            if (!idle && !tooOld(key)) {
                continue;
            }
            ReentrantLock lk = locks.get(key);
            if (lk != null && lk.tryLock()) {
                try {
                    evict(key);
                    lastUsedAt.remove(key);
                } finally {
                    lk.unlock();
                }
            }
        }
    }

    @PreDestroy
    void closeAll() {
        for (GameConnection c : hallPool.values()) {
            closeQuietly(c);
        }
        hallPool.clear();
    }

    private static void closeQuietly(GameConnection c) {
        try {
            c.close();
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    /** 取回包 {@code parameters[0]} 作为对象数组（每元素一个 Map）；空/异型返回空表。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> firstList(ApcObject apc) {
        List<Object> params = apc.getParameters();
        if (params == null || params.isEmpty() || !(params.get(0) instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
