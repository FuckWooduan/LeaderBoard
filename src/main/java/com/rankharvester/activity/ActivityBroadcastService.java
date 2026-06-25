package com.rankharvester.activity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 活动广播：定时抓活动 → 发现<b>新活动</b> → 渲染长图(report.png) → 把事件 publish 到 Redis 频道
 * {@code rankharvester:activity:new}，并把累积的新活动合并成一封邮件发给订阅者。
 *
 * <p>去重：Redis SET {@code rh:activity:announced} 记已播过的 activityId。<b>冷启动（集合为空）只建基线、不推送</b>，
 * 避免上线即把存量活动全刷一遍；之后只播真正新出现的活动。单轮渲染条数有上限（渲染较重）。
 */
@Service
public class ActivityBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(ActivityBroadcastService.class);

    private static final String SEEN_KEY = "rh:activity:announced";
    /** 累积待发邮件的活动批次（Redis 持久化，跨重启不丢、不拆批）。 */
    private static final String PENDING_KEY = "rh:activity:pending";
    /** 每条活动「首次被本站抓到」的时刻（Redis HASH：activityId→epochMs）。只记真正新出现的活动；
     * 存量基线活动无历史抓取时间，不回填假数据，前端显示「—」。 */
    private static final String FIRST_SEEN_KEY = "rh:activity:first_seen";
    /** 单轮最多渲染+推送的新活动数（渲染吃 CPU，防一次性爆发；余下的留到下一轮 15s 后继续）。 */
    private static final int MAX_PER_ROUND = 3;
    /** 收集模式：检测到新活动后改为每 15 秒抓一次。 */
    private static final long BURST_INTERVAL_MS = 15_000L;

    private final ActivityFetcher fetcher;
    private final ActivityImageRenderService render;
    private final ActivityEmailService email;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final boolean enabled;
    private final long intervalMs;

    /** 最近一次抓取的活动 bundle（含 res 上下文，供前端列表 + 按需渲染长图复用，避免每次请求都打 CDN）。 */
    private volatile ActivityFetcher.FetchBundle lastBundle;
    /** 最近一次成功抓取活动的时刻（北京时间前端格式化）。 */
    private volatile long lastFetchAt;
    /** 预计下次检测活动的时刻 = 上轮结束 + 间隔。 */
    private volatile long nextDetectAt;
    /** 是否处于收集模式（检测到新活动后，每 15s 快抓收集，直到静默够久或满兜底上限才合并发邮件）。 */
    private volatile boolean inBurst;
    private volatile long burstStartAt;
    /** 最近一个新活动被发现的时刻（静默判定基准：trickle 进来的活动会不断把它推后，延长收集窗口）。 */
    private volatile long lastNewActivityAt;
    /** 上次实际抓取的时刻（常规节流：非收集期每 {@code intervalMs} 抓一次）。 */
    private volatile long lastFetchTriggerAt;
    /** 收集期累积的新活动（彻底没有新活动后合并成<b>一封</b>邮件发出，避免一次更新发多封）。 */
    private final java.util.List<ActivityEmailService.BatchItem> pending = new java.util.ArrayList<>();
    /** 内存缓存：activityId→「首次抓到」epochMs（启动从 Redis HASH 载入，发现新活动时增量更新）。
     * 用不可变快照 + 整体替换，读侧无锁。 */
    private volatile java.util.Map<Long, Long> firstSeen = java.util.Map.of();

    /** 静默判定：最后一个新活动之后，再过这么久仍无新活动 → 认定本批"完全结束"，合并发一封邮件（默认 5 分钟）。 */
    @Value("${rankharvester.activity.email-quiet-ms:120000}")
    private long emailQuietMs;
    /** 收集兜底上限：从首个新活动起最长收集这么久（防一直有新活动时永不发邮件，默认 45 分钟）。 */
    @Value("${rankharvester.activity.email-burst-max-ms:2700000}")
    private long emailBurstMaxMs;

    public ActivityBroadcastService(
            ActivityFetcher fetcher,
            ActivityImageRenderService render,
            ActivityEmailService email,
            StringRedisTemplate redis,
            @Value("${rankharvester.activity.broadcast-enabled:true}") boolean enabled,
            @Value("${rankharvester.activity.broadcast-interval-ms:60000}") long intervalMs) {
        this.fetcher = fetcher;
        this.render = render;
        this.email = email;
        this.redis = redis;
        this.enabled = enabled;
        this.intervalMs = intervalMs;
    }

    /**
     * 每 15 秒触发一次，但<b>抓取节流</b>：常规期每 {@code intervalMs} 抓一次；一旦检测到新活动进入
     * <b>收集模式</b>——改为每 15 秒抓一次，<b>持续到「最后一个新活动之后静默 {@code emailQuietMs}」或满
     * {@code emailBurstMaxMs} 兜底</b>，把整批新活动<b>合并成一封邮件</b>发出（trickle 进来的活动也不会各发一封）。
     * QQ 推送仍<b>逐条即时</b>发出（解析出一个发一个）。累积批次 Redis 持久化，跨重启不丢、不拆批。
     */
    @Scheduled(fixedDelay = BURST_INTERVAL_MS, initialDelayString = "${rankharvester.activity.broadcast-initial-delay-ms:90000}")
    void tick() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean doFetch = inBurst || lastFetchTriggerAt == 0L || (now - lastFetchTriggerAt >= intervalMs);
        if (!doFetch) {
            return; // 常规期未到间隔、且非突发：本 tick 跳过
        }
        lastFetchTriggerAt = now;
        int newCount = 0;
        try {
            newCount = scanAndCollect();
        } catch (RuntimeException e) {
            log.warn("[活动广播] 失败（忽略，下轮重试）: {}", e.toString());
        }
        if (newCount > 0) {
            lastNewActivityAt = now;
            if (!inBurst) {
                inBurst = true;
                burstStartAt = now;
                log.info("[活动广播] 检测到新活动，进入收集模式（每 15s 抓一次；静默 {} 分钟无新活动或满 {} 分钟才合并发一封邮件）",
                        emailQuietMs / 60000, emailBurstMaxMs / 60000);
            }
        }
        // 合并发出判定（QQ 合并 + 邮件合并）：收集中、且(距最后一个新活动已静默够久) 或 (满兜底上限)。
        // 同样的判定也在独立的 flushTick 里跑一遍——万一本 tick 的抓取 hang 住，flushTick 仍能把合并推送发出去。
        maybeFlush(now);
        nextDetectAt = System.currentTimeMillis() + (inBurst ? BURST_INTERVAL_MS : intervalMs);
    }

    /** 突发结束判定 + flush（QQ 合并 + 邮件合并）。tick 与独立 flushTick 共用；flushPending 内部 synchronized，重复调用安全。 */
    private void maybeFlush(long now) {
        if (!inBurst) {
            return;
        }
        boolean quiet = now - lastNewActivityAt >= emailQuietMs;
        boolean capped = now - burstStartAt >= emailBurstMaxMs;
        if (quiet || capped) {
            inBurst = false;
            flushPending(quiet ? "静默 " + (emailQuietMs / 60000) + " 分钟无新活动，本批彻底结束"
                    : "满 " + (emailBurstMaxMs / 60000) + " 分钟兜底");
        }
    }

    /**
     * 独立的「合并发送」轮询：只按时间判断突发是否结束 → flush，<b>不抓取</b>。与 {@link #tick()} 分开跑在不同调度线程，
     * 避免 tick 里 fetchBundle 抓取 hang 住时把合并推送/邮件也一起堵死（这是之前合并推送发不出去的根因）。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    void flushTick() {
        if (!enabled) {
            return;
        }
        maybeFlush(System.currentTimeMillis());
    }

    /** 抓取 + 检测新活动：渲染并把新活动累积到 {@link #pending}（不在此发邮件）。返回本轮发现的新活动数。 */
    private int scanAndCollect() {
        var bundle = fetcher.fetchBundle();
        lastBundle = bundle;            // 缓存供前端列表 + 按需渲染长图
        lastFetchAt = System.currentTimeMillis();
        var rows = bundle.rows();
        if (rows.isEmpty()) {
            return 0;
        }
        var setOps = redis.opsForSet();
        Set<String> seen = setOps.members(SEEN_KEY);
        boolean cold = seen == null || seen.isEmpty();
        if (cold) {
            String[] ids = rows.stream().map(r -> String.valueOf(r.activityId())).toArray(String[]::new);
            setOps.add(SEEN_KEY, ids);
            log.info("[活动广播] 冷启动基线 {} 条，本次不推送", rows.size());
            return 0;
        }
        int pushed = 0;
        int newCount = 0;
        for (ActivityRow r : rows) {
            if (seen.contains(String.valueOf(r.activityId()))) {
                continue;
            }
            newCount++;
            recordFirstSeenIfAbsent(r.activityId()); // 首次检测到该活动 → 记下抓取时刻（HSETNX，幂等）
            if (pushed >= MAX_PER_ROUND) {
                break; // 余下的留到下一轮 15s 后继续（不标记 seen）
            }
            Path png = render.renderReportPng(r, bundle.resConfigXml(), bundle.resurlBack());
            if (png == null) {
                setOps.add(SEEN_KEY, String.valueOf(r.activityId())); // 渲不出：标记已播，避免每轮重试
                continue;
            }
            synchronized (pending) {
                pending.add(new ActivityEmailService.BatchItem(
                        (int) r.activityId(), r.title(), r.startTime(), r.endTime(), png));
            }
            setOps.add(SEEN_KEY, String.valueOf(r.activityId()));
            pushed++;
        }
        if (pushed > 0) {
            savePending(); // 每有新活动入队就持久化到 Redis，重启不丢、不拆批
        }
        if (newCount > 0) {
            log.info("[活动广播] 本轮新活动 {} 条（处理 {}，累积待发 {}）", newCount, pushed, pendingSize());
        }
        return newCount;
    }

    private int pendingSize() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** 本批彻底结束：把累积的所有新活动合并成<b>一封</b>邮件并行发给所有订阅者（失败重试见 {@link ActivityEmailService}）。 */
    private void flushPending(String reason) {
        java.util.List<ActivityEmailService.BatchItem> toSend;
        synchronized (pending) {
            if (pending.isEmpty()) {
                clearPersistedPending();
                return;
            }
            toSend = new java.util.ArrayList<>(pending);
            pending.clear();
        }
        clearPersistedPending(); // 已取出，清持久化；即使下面发送失败也不重发整批（避免重启后重复发）
        try {
            var recipients = email.allEmails();
            int sent = recipients.isEmpty() ? 0 : email.sendActivityBatch(recipients, toSend);
            log.info("[活动广播] {}，合并 {} 个新活动发 1 封邮件，成功 {}/{} 订阅者",
                    reason, toSend.size(), sent, recipients.size());
        } catch (RuntimeException e) {
            log.warn("[活动广播] 合并邮件发送失败（{} 个活动）: {}", toSend.size(), e.toString());
        }
        // QQ 群/好友：1 个新活动单发、多个合并成一条转发卡片（NapCat OneBot，经 Next /qqbot/push）。
        pushQqBatch(toSend);
    }

    /** 把累积待发批次持久化到 Redis（JSON），跨重启不丢、不拆批。失败只记日志，不影响主流程。 */
    private void savePending() {
        try {
            var arr = new java.util.ArrayList<java.util.Map<String, Object>>();
            synchronized (pending) {
                for (ActivityEmailService.BatchItem it : pending) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("activityId", it.activityId());
                    m.put("title", it.title());
                    m.put("startTime", it.startTime());
                    m.put("endTime", it.endTime());
                    m.put("png", it.png() == null ? null : it.png().toString());
                    arr.add(m);
                }
            }
            redis.opsForValue().set(PENDING_KEY, mapper.writeValueAsString(arr));
        } catch (RuntimeException e) {
            log.warn("[活动广播] 持久化 pending 失败（忽略）: {}", e.toString());
        }
    }

    private void clearPersistedPending() {
        try {
            redis.delete(PENDING_KEY);
        } catch (RuntimeException e) {
            log.warn("[活动广播] 清除 pending 持久化失败（忽略）: {}", e.toString());
        }
    }

    /** 启动时恢复未发完的累积批次（重启不把一批拆成多封/漏发）；恢复后重新进入收集、等待静默再合并发出。 */
    @jakarta.annotation.PostConstruct
    void loadPending() {
        loadFirstSeen(); // 顺带载入「首见时刻」快照
        try {
            String json = redis.opsForValue().get(PENDING_KEY);
            if (json == null || json.isBlank()) {
                return;
            }
            var list = mapper.readValue(json, java.util.List.class);
            int restored = 0;
            synchronized (pending) {
                pending.clear();
                for (Object o : (java.util.List<?>) list) {
                    var m = (java.util.Map<?, ?>) o;
                    int id = m.get("activityId") == null ? 0 : ((Number) m.get("activityId")).intValue();
                    Object png = m.get("png");
                    pending.add(new ActivityEmailService.BatchItem(
                            id,
                            m.get("title") == null ? null : String.valueOf(m.get("title")),
                            m.get("startTime") == null ? null : String.valueOf(m.get("startTime")),
                            m.get("endTime") == null ? null : String.valueOf(m.get("endTime")),
                            png == null ? null : Path.of(String.valueOf(png))));
                    restored++;
                }
            }
            if (restored > 0) {
                long now = System.currentTimeMillis();
                inBurst = true;
                burstStartAt = now;
                // 恢复的批次给一个较短窗口（~45s）即可由 flushTick 合并发出，不必再等满静默期（避免重启后迟迟不发）
                lastNewActivityAt = now - Math.max(0, emailQuietMs - 45_000L);
                log.info("[活动广播] 启动恢复未发邮件的累积批次 {} 个活动，继续收集等待静默", restored);
            }
        } catch (Exception e) {
            log.warn("[活动广播] 恢复 pending 失败（忽略）: {}", e.toString());
        }
    }

    /**
     * 手动播一条指定活动（测试用，不走去重）：抓活动→渲染长图→发 Redis 事件。返回是否成功发出。
     */
    public boolean broadcastOne(int activityId) {
        var bundle = fetcher.fetchBundle();
        ActivityRow row = bundle.rows().stream()
                .filter(r -> r.activityId() == activityId).findFirst().orElse(null);
        if (row == null) {
            return false;
        }
        Path png = render.renderReportPng(row, bundle.resConfigXml(), bundle.resurlBack());
        if (png == null) {
            return false;
        }
        pushQqOne(row); // 手动播一条：直接单发 QQ 群/好友
        return true;
    }

    /** 最近一次抓取的活动 bundle；若启动后还没抓过则现抓一次并缓存（供前端首屏）。可能抛（无可用账号/网络）。 */
    public ActivityFetcher.FetchBundle getOrFetchBundle() {
        var b = lastBundle;
        if (b != null) {
            return b;
        }
        synchronized (this) {
            if (lastBundle == null) {
                lastBundle = fetcher.fetchBundle();
                lastFetchAt = System.currentTimeMillis();
            }
            return lastBundle;
        }
    }

    /** 最近一次成功抓取活动的时刻（epoch ms，0=还没抓过）。 */
    public long lastFetchAt() {
        return lastFetchAt;
    }

    /** 预计下次检测活动的时刻（epoch ms，0=还没排过）。 */
    public long nextDetectAt() {
        return nextDetectAt;
    }

    /** 某活动「首次被本站抓到」的时刻（epoch ms）；存量基线活动无记录返回 0（前端显示「—」）。 */
    public long firstSeenAt(long activityId) {
        Long t = firstSeen.get(activityId);
        return t == null ? 0L : t;
    }

    /** 记下某活动首次被抓到的时刻（HSETNX：仅当尚无记录时写入，幂等）；成功写入则同步更新内存快照。 */
    private void recordFirstSeenIfAbsent(long activityId) {
        try {
            long now = System.currentTimeMillis();
            Boolean wrote = redis.opsForHash()
                    .putIfAbsent(FIRST_SEEN_KEY, String.valueOf(activityId), String.valueOf(now));
            if (Boolean.TRUE.equals(wrote)) {
                var m = new java.util.HashMap<>(firstSeen);
                m.put(activityId, now);
                firstSeen = java.util.Map.copyOf(m);
            }
        } catch (RuntimeException e) {
            log.debug("[活动] 记录首见时刻失败 id={}（忽略）: {}", activityId, e.toString());
        }
    }

    /** 启动时把「首见时刻」HASH 全量载入内存快照（之后由 {@link #recordFirstSeenIfAbsent} 增量维护）。 */
    private void loadFirstSeen() {
        try {
            java.util.Map<Object, Object> h = redis.opsForHash().entries(FIRST_SEEN_KEY);
            if (h == null || h.isEmpty()) {
                return;
            }
            var m = new java.util.HashMap<Long, Long>();
            for (var e : h.entrySet()) {
                try {
                    m.put(Long.parseLong(String.valueOf(e.getKey())), Long.parseLong(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignore) {
                    // 跳过坏值
                }
            }
            firstSeen = java.util.Map.copyOf(m);
            log.info("[活动] 载入 {} 条活动首见时刻", m.size());
        } catch (RuntimeException e) {
            log.warn("[活动] 载入首见时刻失败（忽略）: {}", e.toString());
        }
    }

    // ── QQ 主动推送（NapCat OneBot，经 Next.js /qqbot/push 推到全部群 + 全部好友）─────────

    @Value("${rankharvester.qqbot.push-url:http://127.0.0.1:3100/qqbot/push}")
    private String qqPushUrl;

    /** 本机 Java→Next 推送鉴权令牌（= Next 的 QQBOT_PUSH_TOKEN）；为空则不推 QQ，仅邮件。 */
    @Value("${rankharvester.qqbot.push-token:}")
    private String qqPushToken;

    // 必须强制 HTTP/1.1：Java HttpClient 默认 HTTP/2，会先尝试 h2 升级，而目标 Next.js(Node) 端口不支持 →
    // 报 "HTTP/1.1 header parser received no bytes"，导致 QQ 群推送一直失败（邮件走 JavaMailSender 不受影响）。
    private final java.net.http.HttpClient pushClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private boolean qqPushDisabled() {
        return qqPushUrl == null || qqPushUrl.isBlank() || qqPushToken == null || qqPushToken.isBlank();
    }

    private void postPush(Object body, String label) {
        if (qqPushDisabled()) {
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(qqPushUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Push-Token", qqPushToken)
                        .timeout(java.time.Duration.ofSeconds(120))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                var res = pushClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                log.info("[活动广播] QQ 推送 {} → HTTP {} {}", label, res.statusCode(),
                        res.body() == null ? "" : res.body());
            } catch (Exception e) {
                log.warn("[活动广播] QQ 推送失败 {}: {}", label, e.toString());
            }
        });
    }

    /** 单发：手动播一条活动直接发到全部群 + 好友。失败只记日志，不影响主流程。 */
    private void pushQqOne(ActivityRow r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("activityId", r.activityId());
        m.put("title", r.title());
        m.put("startTime", r.startTime());
        m.put("endTime", r.endTime());
        postPush(m, "activityId=" + r.activityId());
    }

    /** 合并推送：一批新活动合并成「一条」发到全部群 + 好友（1 个单发、多个合并转发）。失败只记日志。 */
    private void pushQqBatch(java.util.List<ActivityEmailService.BatchItem> items) {
        if (items == null || items.isEmpty() || qqPushDisabled()) {
            return;
        }
        var arr = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var it : items) {
            var m = new LinkedHashMap<String, Object>();
            m.put("activityId", it.activityId());
            m.put("title", it.title());
            m.put("startTime", it.startTime());
            m.put("endTime", it.endTime());
            arr.add(m);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("activities", arr);
        postPush(body, items.size() + " 个新活动");
    }

}
