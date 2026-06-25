package com.rankharvester.net;

import com.rankharvester.apc.ApcObject;
import com.rankharvester.apc.ApcValues;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 玩家在线查询（移植自 StrikeGod.Engine 的 PlayerSearchService）。
 *
 * <p>选一个覆盖该大区的账号登录 → APC {@code searchCharacterByName} → {@code getUserLocationData}
 * → 解析在线状态与位置。dry-run 下由 {@link SimulatedGameClient} 产出合成回包；接真实环境时需要
 * 完整登录栈（flashvars/captcha/proxy，待移植）。
 */
@Service
public class PlayerSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSearchService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    /** 位置查询仅为「频道/房间」附加细节，尽力而为；超时不丢弃已知在线状态。 */
    private static final Duration LOCATION_TIMEOUT = Duration.ofSeconds(2);
    /** 前端异步单独补查位置时的超时（缩短为 3s：部分区 getUserLocationData 永不回包，久等纯浪费且占连接锁）。 */
    private static final Duration LOCATION_TIMEOUT_ASYNC = Duration.ofSeconds(3);
    /** 等不到专属查询槽的上限（抓榜不占此槽，正常应秒级可得）。 */
    private static final long SLOT_WAIT_SECONDS = 30;
    /** 某区位置查询连续超时阈值：达到即进入冷却，冷却期内直接返回空位置（不再发包、不占连接锁）。 */
    private static final int LOCATION_DEAD_THRESHOLD = 2;
    /** 位置「不可用」冷却时长：该区位置查询连续超时后，此时长内跳过位置查询直接返回「—」。 */
    private static final long LOCATION_DEAD_COOLDOWN_MS = 5 * 60_000L;

    private final GameLoginService loginService;
    /** 优先复用侦察号常热长连接提速（该区有侦察号则不必重新登录抓榜号、省 ~7s 握手）。 */
    private final ScoutConnectionService scout;

    /**
     * 查询槽<strong>分离</strong>：查人（快，~0.1s）走 searchSlot；查位置（频道/房间，可能 6s 超时）走 locationSlot，
     * 慢的位置查询不再饿死快的查人。两者都独立于抓榜并发。
     */
    private final Semaphore searchSlot = new Semaphore(4, true);
    private final Semaphore locationSlot = new Semaphore(2, true);

    /** 某区位置查询连续超时计数；达 {@link #LOCATION_DEAD_THRESHOLD} 即进入冷却。 */
    private final ConcurrentHashMap<String, Integer> locationFailStreak = new ConcurrentHashMap<>();
    /** 某区位置查询冷却截止时间戳（毫秒）；now &lt; 此值时跳过位置查询直接返回空。 */
    private final ConcurrentHashMap<String, Long> locationDeadUntil = new ConcurrentHashMap<>();

    /** 每区一条<strong>常热登录连接</strong>（连接池）：登录一次后靠内置心跳保活，后续查询复用、跳过 ~7s 握手。 */
    private final ConcurrentHashMap<String, GameConnection> pool = new ConcurrentHashMap<>();
    /** 每区一把锁：同一连接同一时刻只允许一个进行中的 APC 请求，故同区查询串行、跨区并行。 */
    private final ConcurrentHashMap<String, ReentrantLock> serverLocks = new ConcurrentHashMap<>();
    /** 每区连接最近使用时间，用于回收长时间不用的常热连接。 */
    private final ConcurrentHashMap<String, Long> lastUsedAt = new ConcurrentHashMap<>();
    /** 连接闲置超过该时长则回收（心跳会一直保活，故需主动回收以释放资源）。 */
    private static final long IDLE_EVICT_MS = 15 * 60_000L;

    public PlayerSearchService(GameLoginService loginService, ScoutConnectionService scout) {
        this.loginService = loginService;
        this.scout = scout;
    }

    /** 查询玩家（角色 + 在线状态 + 位置）。QQ 机器人用（一次拿全）。 */
    public PlayerSearchResult search(String playerName, int serverId) {
        String server = String.valueOf(serverId);
        return withConn(serverId, searchSlot, conn -> {
            var character = searchCharacterOn(conn, playerName);
            // 在线才查位置；该区位置处于冷却（连续超时）则跳过，直接返回空位置，避免数秒干等。
            var location = (character.isOnline() && !locationDead(server))
                    ? fetchLocation(server, conn, character.characterId(), LOCATION_TIMEOUT)
                    : new PlayerSearchResult.LocationInfo(character.characterId(), "—", 0, false);
            return new PlayerSearchResult(character, location);
        });
    }

    /** 只查角色与在线状态（<b>不</b>查位置）——前端用于先快速返回在线状态，频道/房间随后异步补查。 */
    public PlayerSearchResult.CharacterInfo searchCharacter(String playerName, int serverId) {
        return withConn(serverId, searchSlot, conn -> searchCharacterOn(conn, playerName));
    }

    /** 单独查某在线玩家的位置（频道/房间），供前端拿到在线状态后异步补充。 */
    public PlayerSearchResult.LocationInfo locationOf(int serverId, long charId) {
        String server = String.valueOf(serverId);
        // 该区位置处于冷却：直接返回空，不占连接锁、不发包（部分区 getUserLocationData 永不回包）。
        if (locationDead(server)) {
            return new PlayerSearchResult.LocationInfo(charId, "—", 0, false);
        }
        return withConn(serverId, locationSlot, conn -> fetchLocation(server, conn, charId, LOCATION_TIMEOUT_ASYNC));
    }

    /** 该区位置查询是否处于「不可用」冷却期。 */
    private boolean locationDead(String server) {
        Long until = locationDeadUntil.get(server);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 记录某区位置查询结果：成功→清零；超时→累加，达阈值进入冷却。 */
    private void markLocationResult(String server, boolean ok) {
        if (ok) {
            locationFailStreak.remove(server);
            locationDeadUntil.remove(server);
            return;
        }
        int streak = locationFailStreak.merge(server, 1, Integer::sum);
        if (streak >= LOCATION_DEAD_THRESHOLD) {
            locationDeadUntil.put(server, System.currentTimeMillis() + LOCATION_DEAD_COOLDOWN_MS);
            log.info("[{}] 位置查询连续 {} 次超时，进入 {} 分钟冷却（直接返回「—」）",
                    server, streak, LOCATION_DEAD_COOLDOWN_MS / 60_000);
        }
    }

    @FunctionalInterface
    private interface ConnTask<T> {
        T run(GameConnection conn);
    }

    /**
     * 通用：占指定查询槽后执行 task。<strong>优先复用侦察号常热长连接</strong>（该区配了侦察号 → 走
     * {@link ScoutConnectionService#runOnHall}，省去重新登录抓榜号的 ~7s 握手）；无侦察号或侦察号失败则
     * 回退到抓榜号自有常热连接（该区串行锁 + 复用 + 失败重登重试一次）。
     */
    private <T> T withConn(int serverId, Semaphore slot, ConnTask<T> task) {
        String server = String.valueOf(serverId); // = 登录 id
        boolean acquired;
        try {
            acquired = slot.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("玩家查询被中断");
        }
        if (!acquired) {
            throw new IllegalStateException("玩家查询繁忙，请稍后再试");
        }
        try {
            String district = GameServer.districtOfLoginId(server);
            if (district != null && scout != null && scout.available(district)) {
                try {
                    return scout.runOnHall(district, task::run); // 复用侦察号长连接（其内部自带锁/复用/重登）
                } catch (PlayerNotFoundException e) {
                    throw e; // 查无此人：不回退、不换连接
                } catch (RuntimeException e) {
                    log.info("[{}] 侦察号连接查询失败（{}），回退抓榜号", server, e.getMessage());
                }
            }
            return viaOwnConn(server, task);
        } finally {
            slot.release();
        }
    }

    /** 抓榜号自有常热连接路径（该区串行锁 + 复用 + 失败重登重试一次）。 */
    private <T> T viaOwnConn(String server, ConnTask<T> task) {
        ReentrantLock lock = serverLocks.computeIfAbsent(server, k -> new ReentrantLock(true));
        lock.lock();
        try {
            try {
                return task.run(acquireConn(server));
            } catch (PlayerNotFoundException e) {
                throw e; // 查无此人：连接正常，保留常热连接
            } catch (RuntimeException e) {
                // 复用连接失败（含「僵尸连接」：本地 TCP 还活但上游代理节点隧道已断，isActive 仍 true 却超时）
                // → 一律丢弃重登重试一次。好节点下 search 仅 1-5s 不会超时，故无双倍代价。
                log.info("[{}] 复用连接查询失败（{}），重登重试一次", server, e.getMessage());
                evict(server);
                return task.run(acquireConn(server));
            } finally {
                lastUsedAt.put(server, System.currentTimeMillis());
            }
        } finally {
            lock.unlock();
        }
    }

    /** 取该区常热连接：存在且活着就复用，否则登录一条并入池。调用方已持该区锁。 */
    private GameConnection acquireConn(String server) {
        GameConnection conn = pool.get(server);
        if (conn != null && conn.isActive()) {
            return conn;
        }
        if (conn != null) {
            closeQuietly(conn);
        }
        GameConnection fresh = loginService.login(server); // 失败转移：rc=4 剔除该区并换号
        pool.put(server, fresh);
        return fresh;
    }

    private void evict(String server) {
        GameConnection c = pool.remove(server);
        if (c != null) {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(GameConnection c) {
        try {
            c.close();
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    /** 在连接上 searchCharacterByName 并解析角色；查无此人抛 {@link PlayerNotFoundException}。 */
    private PlayerSearchResult.CharacterInfo searchCharacterOn(GameConnection conn, String playerName) {
        ApcObject sc = conn.call("searchCharacterByName", "onSearchCharacterByName", CALL_TIMEOUT, playerName);
        var character = parseCharacter(sc);
        if (character == null) {
            throw new PlayerNotFoundException(playerName);
        }
        return character;
    }

    /** 取在线玩家位置（频道/房间），<b>尽力而为</b>：超时/失败返回占位（不抛、不影响在线状态）；并更新该区位置可用性。 */
    private PlayerSearchResult.LocationInfo fetchLocation(String server, GameConnection conn, long charId, Duration timeout) {
        try {
            ApcObject loc = conn.call("getUserLocationData", "onGetUserLocationData", timeout, charId);
            markLocationResult(server, true);
            return parseLocation(loc, charId);
        } catch (RuntimeException e) {
            log.info("[{}] getUserLocationData 失败/超时（{}）", server, e.getMessage());
            markLocationResult(server, false);
            return new PlayerSearchResult.LocationInfo(charId, "—", 0, false);
        }
    }

    /** 定期回收闲置过久的常热连接（心跳会一直保活，需主动释放）。 */
    @Scheduled(fixedDelay = 300_000L)
    void evictIdle() {
        long now = System.currentTimeMillis();
        for (String server : new ArrayList<>(pool.keySet())) {
            Long lu = lastUsedAt.get(server);
            if (lu != null && now - lu < IDLE_EVICT_MS) {
                continue;
            }
            ReentrantLock lk = serverLocks.get(server);
            if (lk != null && lk.tryLock()) {
                try {
                    evict(server);
                    lastUsedAt.remove(server);
                } finally {
                    lk.unlock();
                }
            }
        }
    }

    @PreDestroy
    void closeAll() {
        for (GameConnection c : pool.values()) {
            closeQuietly(c);
        }
        pool.clear();
    }

    /** onSearchCharacterByName：parameters[0] 为角色对象。 */
    private PlayerSearchResult.CharacterInfo parseCharacter(ApcObject apc) {
        Map<String, Object> m = firstMap(apc.getParameters());
        if (m == null || m.isEmpty()) {
            return null;
        }
        return new PlayerSearchResult.CharacterInfo(
                asLong(m.get("characterId")),
                asStr(m.get("characterName")),
                asStr(m.get("teamName")),
                (int) asLong(m.get("lv")),
                asBool(m.get("isOnline")),
                asLong(m.get("logoutTime")),
                asLong(m.get("dateline")));
    }

    /** onGetUserLocationData：parameters 为位置数组 [charId, channel, room, _, inGame]。 */
    private PlayerSearchResult.LocationInfo parseLocation(ApcObject apc, long fallbackCharId) {
        List<Object> arr = apc.getParameters();
        if (arr != null && arr.size() == 1 && arr.get(0) instanceof List<?> inner) {
            @SuppressWarnings("unchecked")
            var cast = (List<Object>) inner;
            arr = cast;
        }
        if (arr == null || arr.size() < 3) {
            return new PlayerSearchResult.LocationInfo(fallbackCharId, "未知", 0, false);
        }
        long charId = asLong(arr.get(0));
        String channel = arr.get(1) == null ? "未知" : arr.get(1).toString();
        int room = (int) asLong(arr.get(2));
        boolean inGame = arr.size() >= 5 && asBool(arr.get(4));
        return new PlayerSearchResult.LocationInfo(charId, channel, room, inGame);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMap(List<Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object first = params.get(0);
        if (first instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (first instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static long asLong(Object v) {
        return ApcValues.asLongOr(v, 0L);
    }

    private static boolean asBool(Object v) {
        return ApcValues.asBoolOr(v, false);
    }

    private static String asStr(Object v) {
        return ApcValues.asStrOr(v, "");
    }

    /** 未找到玩家。 */
    public static class PlayerNotFoundException extends RuntimeException {
        public PlayerNotFoundException(String name) {
            super("未找到玩家: " + name);
        }
    }
}
