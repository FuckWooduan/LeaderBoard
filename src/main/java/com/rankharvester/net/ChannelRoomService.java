package com.rankharvester.net;

import static com.rankharvester.apc.ApcValues.asBool;
import static com.rankharvester.apc.ApcValues.asInt;
import static com.rankharvester.apc.ApcValues.asLong;
import static com.rankharvester.apc.ApcValues.asStr;

import com.rankharvester.account.GameAccount;
import com.rankharvester.gamedata.GameConfigNameService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 「频道大厅」协调层：把网页/QQ 的高并发读，收敛成对侦察连接的<strong>每区每窗口一次</strong>实查询。
 *
 * <p><b>怎么协调多个用户看同区/不同区</b>（本类的存在理由）：
 * <ul>
 *   <li><b>按区短 TTL 缓存</b>：频道 {@value #CHANNEL_TTL_MS}ms，命中即返回，不发实包。</li>
 *   <li><b>single-flight 合并</b>：同一缓存键并发刷新只触发<strong>一次</strong>实查询，其余复用结果/旧值；
 *       不同区并行。于是「50 人看同区」与「1 人看同区」对游戏服压力相同。</li>
 *   <li><b>无人看即停</b>：纯轮询驱动，没人看页面就不发包；切回来发一个 {@code getRoomChannel}（~1s）即热。</li>
 * </ul>
 * 实查询委托给 {@link ScoutConnectionService}（独立侦察号连接，与抓榜隔离）。房间走驻留 worker（吃推送，实时）。
 */
@Service
public class ChannelRoomService {

    private static final Logger log = LoggerFactory.getLogger(ChannelRoomService.class);

    /** 频道列表缓存有效期。 */
    private static final long CHANNEL_TTL_MS = 10_000L;
    /** 等待在途 single-flight 结果的上限（略高于底层 CALL_TIMEOUT 15s）。 */
    private static final long AWAIT_MS = 16_000L;
    /** 频道在线占比的 VIP 缓冲（仿 ChannelsProxy.vipNumber）。 */
    private static final int VIP_BUFFER = 300;
    /** 房间驻留 worker 空闲超时：超过这段时间无人看 → 离开频道、释放侦察号槽位。 */
    private static final long ROOM_IDLE_MS = 60_000L;
    /** SSE 连接超时：超过此时长服务端主动结束（客户端 EventSource 会自动重连）。防止废连接堆积占线程/连接。 */
    private static final long SSE_TIMEOUT_MS = 10 * 60_000L;

    private final ScoutConnectionService scout;
    private final GameConfigNameService names;

    private final Map<String, Entry<List<ChannelView>>> channelCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<ChannelView>>> channelFlights = new ConcurrentHashMap<>();

    /** districtId → 该区房间调度器（管被看频道集合 + 号池：号够驻留、不够轮巡）。 */
    private final Map<String, RoomScheduler> schedulers = new ConcurrentHashMap<>();
    /**
     * 房间调度专用线程池：所有「进频道/roomList/连接」等阻塞 IO 在此跑，
     * <strong>不占 HTTP 请求线程、不占 Spring @Scheduled 单线程</strong>。各区并行、互不阻塞。
     */
    private final java.util.concurrent.ExecutorService roomExec =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "room-sched");
                t.setDaemon(true);
                return t;
            });

    public ChannelRoomService(ScoutConnectionService scout, GameConfigNameService names) {
        this.scout = scout;
        this.names = names;
    }

    /** 频道视图（含在线人数/容量/拥挤状态）。 */
    public record ChannelView(int id, String name, int number, int maxClient, int percent,
                              String state, int limitMinLv, int limitMaxLv, int position,
                              String serverDomain) {}

    /** 房间视图（玩法/地图已解析中文名）。 */
    public record RoomView(long roomId, long displayId, String name, String mode, String map,
                           int access, int limit, int status, boolean encrypt, String owner,
                           int channelId, int friendNumber) {}

    /** 某区是否可看（配了侦察号）。 */
    public boolean available(String district) {
        return scout.available(district);
    }

    /** 某区频道列表（含在线人数）。未配侦察号抛 {@link IllegalArgumentException}。 */
    public List<ChannelView> channels(String district) {
        return loadChannels(district);
    }

    private List<ChannelView> loadChannels(String district) {
        return cached(channelCache, channelFlights, district, CHANNEL_TTL_MS,
                () -> mapChannels(district, scout.channelList(district)));
    }

    // ───────── 频道列表 / 主页：SSE 推送（后端按区定时拉 getRoomChannel → 推订阅者；主页与列表同源一致）─────────

    /** districtId → 该区频道列表的 SSE 订阅者（点进某区看频道列表）。 */
    private final Map<String, java.util.Set<SseEmitter>> channelSubs = new ConcurrentHashMap<>();

    /** 订阅某区频道列表实时推送：建连推一帧当前列表，之后后端每 5s 拉一次推过来。 */
    public SseEmitter subscribeChannels(String district) {
        if (!scout.available(district)) {
            throw new IllegalArgumentException("该区未配置侦察号: " + district);
        }
        var em = new SseEmitter(SSE_TIMEOUT_MS);
        channelSubs.computeIfAbsent(district, k -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(em);
        em.onCompletion(() -> removeChannelSub(district, em));
        em.onTimeout(() -> removeChannelSub(district, em));
        em.onError(e -> removeChannelSub(district, em));
        try {
            em.send(SseEmitter.event().name("channels").data(loadChannels(district)));
        } catch (IOException e) {
            removeChannelSub(district, em);
        }
        return em;
    }

    private void removeChannelSub(String district, SseEmitter em) {
        var set = channelSubs.get(district);
        if (set != null) set.remove(em);
    }

    /**
     * 后端定时刷新（每 5s）：对「有频道列表订阅者的区」各拉一次 getRoomChannel，把频道列表推给该区订阅者。
     * <strong>每区只一个号一个包</strong>（换号兜底）。
     */
    @Scheduled(fixedDelay = 5_000L)
    void refreshChannels() {
        var districts = new java.util.LinkedHashSet<String>();
        for (var e : channelSubs.entrySet()) {
            if (!e.getValue().isEmpty()) districts.add(e.getKey());
        }
        if (districts.isEmpty()) {
            return; // 没人看 → 不发包
        }
        for (String d : districts) {
            List<ChannelView> list;
            try {
                list = loadChannels(d); // 10s 缓存 + single-flight
            } catch (RuntimeException ex) {
                continue;
            }
            pushChannels(d, list);
        }
    }

    private void pushChannels(String district, List<ChannelView> list) {
        var set = channelSubs.get(district);
        if (set == null || set.isEmpty()) return;
        for (SseEmitter em : set) {
            try {
                em.send(SseEmitter.event().name("channels").data(list));
            } catch (Exception e) {
                set.remove(em);
                em.completeWithError(e);
            }
        }
    }

    /** SSE 心跳：每 20s 给所有订阅者发一个注释帧，保活 + 让已断开的连接被及时清理（send 抛错即移除）。 */
    @Scheduled(fixedDelay = 20_000L)
    void sseHeartbeat() {
        for (var set : channelSubs.values()) {
            pingAll(set);
        }
        for (RoomScheduler sch : schedulers.values()) {
            for (var st : sch.activeStates()) {
                pingAll(st.subscribers());
            }
        }
    }

    private static void pingAll(java.util.Set<SseEmitter> set) {
        if (set == null) return;
        for (SseEmitter em : set) {
            try {
                em.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                set.remove(em);
                try { em.completeWithError(e); } catch (RuntimeException ignore) { }
            }
        }
    }

    /**
     * 某区某频道的房间列表（一次性快照）：确保该频道被调度（号够即驻留、不够则轮巡），返回当前内存快照。
     * 实时推送请用 {@link #subscribe}。
     */
    public List<RoomView> rooms(String district, int channelId) {
        if (!scout.available(district)) {
            throw new IllegalArgumentException("该区未配置侦察号: " + district);
        }
        preflightLevel(district, channelId); // 等级不匹配 → 立刻精确报错，不进调度
        RoomScheduler sch = scheduler(district);
        ChannelRoomState st = sch.ensureChannel(channelId, this::broadcast);
        st.touch();
        kickTick(sch);                        // 异步催一拍（不在请求线程做阻塞 IO）
        return viewsOf(st.snapshot());        // 立即返回当前快照（首次可能空，随后由 SSE/轮询补上）
    }

    /**
     * 订阅某频道房间<strong>实时推送</strong>（SSE）：纳入该区调度——号够该频道独占一号<b>驻留</b>（吃推送毫秒级），
     * 号不够则该区号在多余频道间<b>尽快轮巡</b>刷新（刷完即走）。建连先推一帧当前快照，进频道由后台调度异步完成。
     */
    public SseEmitter subscribe(String district, int channelId) {
        if (!scout.available(district)) {
            throw new IllegalArgumentException("该区未配置侦察号: " + district);
        }
        preflightLevel(district, channelId);
        RoomScheduler sch = scheduler(district);
        ChannelRoomState st = sch.ensureChannel(channelId, this::broadcast);
        var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        st.addSubscriber(emitter);
        emitter.onCompletion(() -> st.removeSubscriber(emitter));
        emitter.onTimeout(() -> st.removeSubscriber(emitter));
        emitter.onError(e -> st.removeSubscriber(emitter));
        kickTick(sch);                        // 异步催一拍（不阻塞请求线程）
        try {
            emitter.send(SseEmitter.event().name("rooms").data(viewsOf(st.snapshot())));
        } catch (IOException e) {
            st.removeSubscriber(emitter);
        }
        return emitter;
    }

    /** 异步催一拍该区调度（在调度线程池跑，绝不阻塞 HTTP 请求线程）。 */
    private void kickTick(RoomScheduler sch) {
        roomExec.execute(() -> {
            try {
                sch.tick(ROOM_IDLE_MS);
            } catch (RuntimeException e) {
                log.warn("[room] 即时调度异常: {}", e.toString());
            }
        });
    }

    /** 某频道快照变更（驻留 push / 轮巡刷新）→ 推给该频道所有 SSE 订阅者。 */
    private void broadcast(ChannelRoomState st) {
        var set = st.subscribers();
        if (set.isEmpty()) {
            return;
        }
        var event = st.unavailableReason() != null
                ? SseEmitter.event().name("unavailable").data(st.unavailableReason())
                : SseEmitter.event().name("rooms").data(viewsOf(st.snapshot()));
        for (SseEmitter em : set) {
            try {
                em.send(event);
            } catch (Exception e) {
                set.remove(em);
                em.completeWithError(e);
            }
        }
    }

    private List<RoomView> viewsOf(List<Map<String, Object>> raw) {
        return raw.stream().map(this::mapRoom).toList();
    }

    /** 取/建该区调度器。 */
    private RoomScheduler scheduler(String district) {
        return schedulers.computeIfAbsent(district,
                d -> new RoomScheduler(d, scout, chId -> levelRange(d, chId)));
    }

    /** 该频道等级门槛 [min,max]（从已缓存频道列表取；取不到回退 [0,0]=不限）。 */
    private int[] levelRange(String district, int channelId) {
        ChannelView ch = loadChannels(district).stream()
                .filter(c -> c.id() == channelId).findFirst().orElse(null);
        return ch == null ? new int[]{0, 0} : new int[]{ch.limitMinLv(), ch.limitMaxLv()};
    }

    /** 进调度前的等级预筛：该区<strong>没有任何号</strong>能进该频道 → 立刻精确报错（不浪费连接）。 */
    private void preflightLevel(String district, int channelId) {
        int[] r = levelRange(district, channelId);
        int min = r[0], max = r[1];
        var pool = scout.scoutsOf(district);
        boolean anyFit = false;
        boolean anyUnknown = false;
        for (GameAccount a : pool) {
            int lv = scout.knownLevel(a, district); // -1=未知
            if (lv < 0) {
                anyUnknown = true;
            } else if (lv >= min && (max <= 0 || lv <= max)) {
                anyFit = true;
                break;
            }
        }
        // 已知里有匹配 → 放行；都不匹配但有未知 → 放行（调度时懒登再判）；已知全不匹配且无未知 → 报错
        if (!anyFit && !anyUnknown && !pool.isEmpty()) {
            throw new IllegalStateException(min == 0 && max == 0
                    ? "无可用侦察号进入该频道"
                    : String.format("该频道需 %d–%d 级账号，当前侦察号均不匹配（请为该区补一个该等级区间的号）", min, max));
        }
    }

    /**
     * 周期驱动各区调度器（号分配/驻留维持/轮巡刷新/不活跃回收）。各区<strong>并行</strong>提交到 {@link #roomExec}，
     * 一个慢区的阻塞 IO 不拖累别的区，也不占用 Spring 的 @Scheduled 单线程（心跳/概览刷新照常）。
     */
    @Scheduled(fixedDelay = 3_000L)
    void driveSchedulers() {
        for (RoomScheduler sch : schedulers.values()) {
            roomExec.execute(() -> {
                try {
                    sch.tick(ROOM_IDLE_MS);
                } catch (RuntimeException e) {
                    log.warn("[room] 调度 tick 异常: {}", e.toString());
                }
            });
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        roomExec.shutdownNow();
        for (RoomScheduler sch : schedulers.values()) {
            sch.shutdown();
        }
        schedulers.clear();
    }

    private RoomView mapRoom(Map<String, Object> r) {
        int raceType = asInt(r, "raceType");
        int sceneId = asInt(r, "sceneId");
        int status = asInt(r, "status");
        int gameStatus = asInt(r, "gameStatus");
        return new RoomView(
                asLong(r, "roomId"),
                asLong(r, "roomDisplayId"),
                asStr(r, "roomName"),
                names.modeName(raceType),
                names.mapName(sceneId),
                asInt(r, "accessNumber"),
                asInt(r, "gameLimitNumber"),
                roomStatus(status, gameStatus),
                asBool(r, "isEncrypt"),
                asStr(r, "createCharName"),
                asInt(r, "channelId"),
                asInt(r, "friendNumber"));
    }

    /** 房间显示状态：0=等待 1=准备 2=战斗中（仿 SearchRoomProxy.getGameStatus）。 */
    private static int roomStatus(int status, int gameStatus) {
        if (status == 0) {
            return 0;
        }
        if (status == 2) {
            return gameStatus + 1;
        }
        return status;
    }

    /**
     * 缓存 + single-flight 核心：新鲜命中直接返回；过期则<strong>每键仅一个</strong>刷新在途，
     * 其余调用有旧值返旧值（不阻塞），无旧值则等在途结果。
     */
    private <T> T cached(Map<String, Entry<T>> cache, Map<String, CompletableFuture<T>> flights,
                         String key, long ttlMs, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Entry<T> e = cache.get(key);
        if (e != null && now - e.ts() < ttlMs) {
            return e.value(); // 新鲜命中
        }
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<T> running = flights.putIfAbsent(key, mine);
        if (running != null) {
            return e != null ? e.value() : await(running); // 有旧值不阻塞；无旧值等在途
        }
        try {
            T value = loader.get();
            cache.put(key, new Entry<>(value, System.currentTimeMillis()));
            mine.complete(value);
            return value;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            if (e != null) {
                return e.value(); // 刷新失败但有旧值 → 返旧值
            }
            throw ex;
        } finally {
            flights.remove(key, mine);
        }
    }

    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.get(AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("查询繁忙，请稍后再试");
        }
    }

    private List<ChannelView> mapChannels(String district, List<Map<String, Object>> raw) {
        var out = new ArrayList<ChannelView>(raw.size());
        for (var m : raw) {
            int number = asInt(m, "number");
            int maxClient = asInt(m, "maxClient");
            int cid = asInt(m, "id");
            out.add(new ChannelView(
                    cid, asStr(m, "name"), number, maxClient,
                    percent(number, maxClient), state(number, maxClient),
                    asInt(m, "limitMinLV"), asInt(m, "limitMaxLV"), asInt(m, "position"),
                    asStr(m, "serverDomain")));
        }
        out.sort(Comparator.comparingInt(ChannelView::position));
        return out;
    }

    /** 进度条百分比（0–100，按 number/maxClient）。 */
    private static int percent(int number, int maxClient) {
        if (maxClient <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(100f * number / maxClient)));
    }

    /** 拥挤状态（仿 ChannelsProxy.setChannelList：free/crowd/full/vip）。 */
    private static String state(int number, int maxClient) {
        double raw = maxClient > VIP_BUFFER ? number / (double) (maxClient - VIP_BUFFER) : 2.0;
        if (raw < 0.5) {
            return "free";
        }
        if (raw < 0.9) {
            return "crowd";
        }
        if (number >= maxClient) {
            return "full";
        }
        return "vip";
    }

    record Entry<T>(T value, long ts) {}
}
