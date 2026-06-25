package com.rankharvester.net;

import com.rankharvester.account.GameAccount;
import com.rankharvester.apc.ApcObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>每区房间调度器</strong>：管该区「被看频道集合」+「侦察号池」，决定每个频道是
 * <b>驻留</b>（号够 → 独占一个号、吃推送、毫秒实时）还是<b>轮巡</b>（号不够 → 号在多余频道间尽快轮流刷）。
 *
 * <p><b>线程模型（关键）</b>：{@link #tick()} 由 {@code ChannelRoomService} 的后台调度线程<strong>单独</strong>驱动，
 * <strong>不在 HTTP 请求线程上跑</strong>。tick 内部「决策」与「阻塞 IO」严格分离：
 * <ul>
 *   <li><b>决策</b>（{@link #plan}）：纯内存、瞬时，在 {@code residents}/{@code states} 的短临界区内算出
 *       「停哪些驻留 / 哪些频道用哪个号起驻留 / 哪些频道轮巡」。</li>
 *   <li><b>执行</b>（{@link #execute}）：所有 enterChannel/roomList/连接/关闭等<strong>阻塞网络调用都在锁外</strong>，
 *       故慢登录/慢网络不会卡住别的频道、别的区、或 SSE 心跳。</li>
 * </ul>
 * 房间快照与 SSE 订阅者归 {@link ChannelRoomState}（与号解耦），驻留↔轮巡切换对前端无感。
 */
final class RoomScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoomScheduler.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

    private final String district;
    private final ScoutConnectionService scout;
    private final java.util.function.Function<Integer, int[]> levelRangeOf; // channelId → [min,max]

    /** channelId → 该频道房间态（被看才有）。 */
    private final Map<Integer, ChannelRoomState> states = new ConcurrentHashMap<>();
    /** 驻留中的频道：channelId → 驻留连接（吃推送）。仅在 tick 线程读写。 */
    private final Map<Integer, Resident> residents = new ConcurrentHashMap<>();
    /** 轮巡游标。仅在 tick 线程读写。 */
    private int rrCursor;

    RoomScheduler(String district, ScoutConnectionService scout,
                  java.util.function.Function<Integer, int[]> levelRangeOf) {
        this.district = district;
        this.scout = scout;
        this.levelRangeOf = levelRangeOf;
    }

    /** 一条驻留连接：某号在某频道，连接保持、吃 add/change/delete 推送。 */
    private record Resident(GameAccount account, GameConnection conn) {}

    ChannelRoomState state(int channelId) {
        return states.get(channelId);
    }

    /** 当前所有被看频道的房间态（SSE 心跳遍历用）。 */
    java.util.Collection<ChannelRoomState> activeStates() {
        return states.values();
    }

    /** 确保某频道被纳入调度（被订阅时调用）；返回其房间态。仅登记，不做任何阻塞 IO。 */
    ChannelRoomState ensureChannel(int channelId, java.util.function.Consumer<ChannelRoomState> broadcaster) {
        return states.computeIfAbsent(channelId, id -> {
            var st = new ChannelRoomState(district, id);
            st.setBroadcaster(broadcaster);
            return st;
        });
    }

    // ─────────────────────── 调度一拍：决策（瞬时）→ 执行（阻塞 IO，锁外）───────────────────────

    /** tick 由后台单线程调度器调用（不在请求线程）。决策瞬时，执行的阻塞 IO 全在锁外。 */
    void tick(long idleMs) {
        Plan plan = plan(idleMs);   // 纯内存决策（含短临界区）
        execute(plan);              // 阻塞 IO，无锁
    }

    /** 一拍的执行计划：要关的连接、要起驻留的(频道,号)、要轮巡的频道。 */
    private record Plan(List<GameConnection> toClose,
                        List<int[]> toStartResident,            // [channelId] + 选号在 accounts 平行表
                        List<GameAccount> startAccounts,
                        List<int[]> toRoam,                     // [channelId]
                        List<GameAccount> roamAccounts) {}

    /** 决策：纯内存、瞬时。回收不活跃频道、按号数决定驻留/轮巡、选号（已知等级，不触发登录）。 */
    private Plan plan(long idleMs) {
        var toClose = new ArrayList<GameConnection>();
        var startCh = new ArrayList<int[]>();
        var startAcc = new ArrayList<GameAccount>();
        var roamCh = new ArrayList<int[]>();
        var roamAcc = new ArrayList<GameAccount>();
        long now = System.currentTimeMillis();

        synchronized (this) {
            // 1) 回收不活跃频道（无订阅者且超过 idle）：收集要关的连接，移除状态/驻留
            for (var e : new ArrayList<>(states.entrySet())) {
                ChannelRoomState st = e.getValue();
                if (!st.hasSubscribers() && now - st.lastViewedAt() > idleMs) {
                    states.remove(e.getKey());
                    Resident r = residents.remove(e.getKey());
                    if (r != null) toClose.add(r.conn());
                }
            }
            if (states.isEmpty()) {
                residents.values().forEach(r -> toClose.add(r.conn()));
                residents.clear();
                return new Plan(toClose, startCh, startAcc, roamCh, roamAcc);
            }

            List<GameAccount> pool = scout.scoutsOf(district);
            List<Integer> active = new ArrayList<>(states.keySet());
            boolean enough = active.size() <= pool.size();

            // 清理失效驻留（连接已断 / 频道已不活跃）
            for (var e : new ArrayList<>(residents.entrySet())) {
                Resident r = e.getValue();
                if (!states.containsKey(e.getKey()) || !r.conn().isActive()) {
                    residents.remove(e.getKey());
                    toClose.add(r.conn());
                }
            }

            if (enough) {
                // 号够：每频道一个专属号驻留。只对「没驻留」的频道起；不动已驻留的（避免抖动）
                var busy = new java.util.HashSet<String>();
                residents.values().forEach(r -> busy.add(r.account().id()));
                for (int chId : active) {
                    if (residents.containsKey(chId)) continue;
                    GameAccount a = pickKnownAccount(chId, pool, busy);
                    if (a != null) {           // 已知等级匹配 → 计划起驻留
                        startCh.add(new int[]{chId});
                        startAcc.add(a);
                        busy.add(a.id());
                    } else {                   // 等级未知 → 本拍先轮巡(顺带懒登抓等级)，下一拍再决定驻留
                        GameAccount any = firstFree(pool, busy);
                        if (any != null) { roamCh.add(new int[]{chId}); roamAcc.add(any); }
                    }
                }
            } else {
                // 号不够：不保留驻留，全部号轮巡。只关「多余的」驻留——实际上号不够时无法每频道驻留，全部转轮巡。
                residents.values().forEach(r -> toClose.add(r.conn()));
                residents.clear();
                planRoam(active, pool, roamCh, roamAcc);
            }
        }
        return new Plan(toClose, startCh, startAcc, roamCh, roamAcc);
    }

    /**
     * 轮巡分配（号不够时）：从游标处起，给每个频道配一个<strong>能进它</strong>的空闲号（够级或等级未知；
     * 等级不够的配对直接跳过，避免空跑一趟进不去）。游标推进保证逐拍覆盖所有频道。仅在 plan 的临界区内调用。
     */
    private void planRoam(List<Integer> active, List<GameAccount> pool,
                          List<int[]> roamCh, List<GameAccount> roamAcc) {
        if (active.isEmpty() || pool.isEmpty()) return;
        var usedAcc = new java.util.HashSet<String>();
        int assigned = 0;
        // 从游标起遍历频道，每个频道找一个能进它的空闲号；本拍最多分配「号数」个（每号刷一个频道）
        for (int i = 0; i < active.size() && assigned < pool.size(); i++) {
            int chId = active.get((rrCursor + i) % active.size());
            int[] range = levelRangeOf.apply(chId);
            for (GameAccount a : pool) {
                if (usedAcc.contains(a.id())) continue;
                int lv = scout.knownLevel(a, district); // -1=未知
                if (lv < 0 || fits(lv, range[0], range[1])) { // 够级或等级未知（懒登顺带抓）
                    roamCh.add(new int[]{chId});
                    roamAcc.add(a);
                    usedAcc.add(a.id());
                    assigned++;
                    break;
                }
            }
        }
        rrCursor = (rrCursor + pool.size()) % active.size();
    }

    /** 执行计划里的所有阻塞 IO（无锁）：关连接、起驻留、轮巡刷新。 */
    private void execute(Plan plan) {
        for (GameConnection c : plan.toClose()) {
            closeQuietly(c);
        }
        for (int i = 0; i < plan.toStartResident().size(); i++) {
            int chId = plan.toStartResident().get(i)[0];
            startResident(plan.startAccounts().get(i), chId);
        }
        for (int i = 0; i < plan.toRoam().size(); i++) {
            int chId = plan.toRoam().get(i)[0];
            patrolOnce(plan.roamAccounts().get(i), chId);
        }
    }

    /**
     * 选号（不触发登录）：在「空闲且已知等级匹配」的号里挑<strong>等级最低</strong>的——够格里选最省的，
     * 把高级号留给只有它能进的高级频道。无匹配返回 null（交给轮巡顺带懒登）。
     */
    private GameAccount pickKnownAccount(int channelId, List<GameAccount> pool, java.util.Set<String> busy) {
        int[] range = levelRangeOf.apply(channelId);
        int min = range[0], max = range[1];
        GameAccount best = null;
        int bestLv = Integer.MAX_VALUE;
        for (GameAccount a : pool) {
            if (busy.contains(a.id())) continue;
            int lv = scout.knownLevel(a, district); // 只查缓存，不登录；-1=未知
            if (lv >= 0 && fits(lv, min, max) && lv < bestLv) {
                best = a;
                bestLv = lv;
            }
        }
        return best;
    }

    private static GameAccount firstFree(List<GameAccount> pool, java.util.Set<String> busy) {
        for (GameAccount a : pool) {
            if (!busy.contains(a.id())) return a;
        }
        return null;
    }

    private static boolean fits(int lv, int min, int max) {
        return lv >= min && (max <= 0 || lv <= max);
    }

    /** 一次轮巡（锁外执行）：等级校验(可懒登) → 进频道 → roomList → 写快照+广播 → 离开。 */
    private void patrolOnce(GameAccount account, int channelId) {
        ChannelRoomState st = states.get(channelId);
        if (st == null) return;
        int[] range = levelRangeOf.apply(channelId);
        int lv = scout.levelOf(account, district); // 锁外懒登抓等级；-1=未知(登录失败)
        if (lv >= 0 && !fits(lv, range[0], range[1])) {
            return; // 该号进不去这个频道，跳过（别的号下拍再试）
        }
        try {
            if (scout.authorizeChannel(account, district, channelId) != 1) {
                healLevel(account, channelId); // 授权被拒：疑似缓存等级过期 → 忘掉，下拍重探
                return;
            }
            String[] hp = scout.channelEndpoint(district, channelId);
            String token = scout.hallToken(account, district);
            if (hp == null || token == null) return;
            GameConnection ch = scout.connectChannelServer(
                    "roam-" + account.id() + "-ch" + channelId, hp[0], Integer.parseInt(hp[1]));
            try {
                ApcObject enter = ch.call("enterChannel", "callEnterChannelSuccess", CALL_TIMEOUT, token, channelId);
                if (!(enter.param(0) instanceof Number n) || n.intValue() != 1) {
                    healLevel(account, channelId);
                    return;
                }
                ApcObject roomsApc = ch.call("roomList", "callBackShowRoomList", CALL_TIMEOUT);
                st.replaceAll(extractRooms(roomsApc.getParameters())); // 写快照（锁外广播，见 ChannelRoomState）
            } finally {
                closeQuietly(ch);
            }
        } catch (RuntimeException e) {
            log.debug("[room {}#{}] 轮巡刷新失败: {}", district, channelId, e.getMessage());
        }
    }

    /**
     * 等级自愈：进频道被拒（授权/enterChannel 失败）→ 忘掉该 (号,区) 等级缓存，下拍懒登重抓新等级。
     * 节流：仅当缓存里<strong>有</strong>已知等级时才忘（避免「真·权限问题」反复 forget 空转）。
     */
    private void healLevel(GameAccount account, int channelId) {
        if (scout.knownLevel(account, district) >= 0) {
            scout.forgetLevel(account, district);
            log.info("[room {}#{}] 号 {} 进频道被拒，重探该区等级", district, channelId, account.id());
        }
    }

    /** 起一个驻留连接（锁外执行）：enterChannel + 注册增量监听 + 首次 roomList，成功才登记进 residents。 */
    private void startResident(GameAccount account, int channelId) {
        ChannelRoomState st = states.get(channelId);
        if (st == null) return;
        try {
            if (scout.authorizeChannel(account, district, channelId) != 1) {
                healLevel(account, channelId); // 疑似缓存等级过期 → 重探
                st.markUnavailable("侦察号无法进入该频道（授权被拒）");
                return;
            }
            String[] hp = scout.channelEndpoint(district, channelId);
            String token = scout.hallToken(account, district);
            if (hp == null || token == null) {
                st.markUnavailable("频道地址或登录令牌不可用");
                return;
            }
            GameConnection ch = scout.connectChannelServer(
                    "scout-" + account.id() + "-ch" + channelId, hp[0], Integer.parseInt(hp[1]));
            boolean ok = false;
            try {
                ApcObject enter = ch.call("enterChannel", "callEnterChannelSuccess", CALL_TIMEOUT, token, channelId);
                if (!(enter.param(0) instanceof Number n) || n.intValue() != 1) {
                    healLevel(account, channelId);
                    return;
                }
                ch.addPushListener("callBackAddRoom", a -> onAddOrChange(st, a));
                ch.addPushListener("callBackChangeRoomData", a -> onAddOrChange(st, a));
                ch.addPushListener("callBackDeleteRoom", a -> onDelete(st, a));
                ApcObject roomsApc = ch.call("roomList", "callBackShowRoomList", CALL_TIMEOUT);
                st.replaceAll(extractRooms(roomsApc.getParameters()));
                // 频道仍活跃才登记驻留；否则连接已无用，关掉（plan 与 execute 之间频道可能已被回收）
                synchronized (this) {
                    if (states.containsKey(channelId)) {
                        residents.put(channelId, new Resident(account, ch));
                        ok = true;
                    }
                }
                log.info("[room {}#{}] 号 {} 驻留（吃推送实时）", district, channelId, account.id());
            } finally {
                if (!ok) closeQuietly(ch);
            }
        } catch (RuntimeException e) {
            log.info("[room {}#{}] 驻留失败: {}", district, channelId, e.getMessage());
        }
    }

    private void onAddOrChange(ChannelRoomState st, ApcObject apc) {
        List<Object> p = apc.getParameters();
        if (p == null || p.size() < 2 || !(p.get(1) instanceof Map<?, ?> m)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> room = (Map<String, Object>) m;
        if (p.size() >= 3 && p.get(2) instanceof Number fn) {
            room.put("friendNumber", fn.intValue());
        }
        st.upsert(room);
    }

    private void onDelete(ChannelRoomState st, ApcObject apc) {
        List<Object> p = apc.getParameters();
        if (p != null && p.size() >= 2 && p.get(1) instanceof Number n) {
            st.remove(n.doubleValue());
        }
    }

    void shutdown() {
        List<GameConnection> toClose;
        synchronized (this) {
            toClose = new ArrayList<>();
            residents.values().forEach(r -> toClose.add(r.conn()));
            residents.clear();
            states.clear();
        }
        toClose.forEach(RoomScheduler::closeQuietly);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractRooms(List<Object> params) {
        if (params == null || params.isEmpty() || !(params.get(0) instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static void closeQuietly(GameConnection c) {
        if (c == null) return;
        try { c.close(); } catch (RuntimeException ignore) { }
    }
}
