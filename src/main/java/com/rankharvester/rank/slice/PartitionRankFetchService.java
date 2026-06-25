package com.rankharvester.rank.slice;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.net.GameClient;
import com.rankharvester.net.GameConnection;
import static com.rankharvester.apc.ApcValues.asInt;
import com.rankharvester.rank.fetch.parse.ReducedRankParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 分区<b>排行榜数据</b>抓取：对每个有号覆盖的 (rankKey, 分区)，从该分区取一个号登录，发
 * {@code getReducedRankByPage(rankKey, 0, 1000, 0)}（一分区 ≤1000，不翻页），复用 {@link ReducedRankParser}
 * 解析后整段写入 {@code partition_rank}。
 *
 * <p>静态 rankKey 抓一次即可（已抓过的分区跳过）；动态 rankKey 每 30 分钟刷新。并发抓（默认 6）。
 */
@Service
public class PartitionRankFetchService {

    private static final Logger log = LoggerFactory.getLogger(PartitionRankFetchService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(25);
    private static final int PART_FETCH_MAX_TRY = 5; // 单分区即刻抓取：登录失败就换号/换IP，最多试几个号

    private final GameClient gameClient;
    private final com.rankharvester.net.PandaProxyService pandaProxy;
    private final AccountRegistry accounts;
    private final com.rankharvester.account.GameAccountStore accountStore;
    private final RankSliceStore sliceStore;
    private final PartitionRankStore rankStore;
    private final SliceSubscriptionService subscriptions;
    private final ReducedRankParser parser = new ReducedRankParser();
    private final int concurrency;
    private final boolean enabled;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final ConcurrentHashMap<String, GameAccount> accountById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    /** 「即刻抓取」路径（整榜/单区）已提交但未完成的分区数；不走 inFlight，单独计数供进度条分母用。 */
    private final java.util.concurrent.atomic.AtomicInteger immediatePending =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 调度路径：当前<b>真实待抓</b>（已认领、未抓完）的分区数。空转来源(已抓过/被认领→跳过)<b>不</b>计入，
     *  所以进度分母=真实待抓分区数(几百)而非账号来源数(上万)，进度条才平滑可信。每来源 +tasks/-tasks 单加单减平衡。 */
    private final java.util.concurrent.atomic.AtomicInteger slicePartitions =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 本轮已认领的 (rankKey#分区)，避免多个号重复抓同一分区；登录/抓取失败会放回让别的号重试。 */
    private final ConcurrentHashMap<String, Boolean> claimed = new ConcurrentHashMap<>();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rowsWritten = new AtomicLong();
    private volatile long lastRunAt = 0L;

    // 季末抢数：临时拉高到 seasonConcurrency 并发、循环重试到 200 区全齐。
    private final int seasonConcurrency;
    private final String seasonEndDate;                       // YYYY-MM-DD（北京）；今天=此日则 22:03 自动跑；空=只手动
    private static final long SEASON_PREPARE_MS = 6L * 60 * 60_000L;
    private volatile long seasonFreshFloor = 0L;              // >0=季末模式：跳过「本轮已抓到」(updated_at>=此值) 的分区
    private final AtomicBoolean seasonRunning = new AtomicBoolean(false);
    private volatile String seasonStatus = "idle";           // idle / running:第N波 缺M / done:缺M
    private final Set<String> autoSeasonTriggered = ConcurrentHashMap.newKeySet();
    private final com.rankharvester.rank.fetch.FetchActivityService activity;

    public PartitionRankFetchService(
            GameClient gameClient,
            com.rankharvester.net.PandaProxyService pandaProxy,
            AccountRegistry accounts,
            com.rankharvester.account.GameAccountStore accountStore,
            RankSliceStore sliceStore,
            PartitionRankStore rankStore,
            SliceSubscriptionService subscriptions,
            com.rankharvester.rank.fetch.FetchActivityService activity,
            @Value("${rankharvester.partition.fetch-concurrency:6}") int concurrency,
            @Value("${rankharvester.partition.fetch-enabled:true}") boolean enabled,
            @Value("${rankharvester.partition.season-concurrency:60}") int seasonConcurrency,
            @Value("${rankharvester.partition.season-end-date:}") String seasonEndDate) {
        this.gameClient = gameClient;
        this.pandaProxy = pandaProxy;
        this.accounts = accounts;
        this.accountStore = accountStore;
        this.sliceStore = sliceStore;
        this.rankStore = rankStore;
        this.subscriptions = subscriptions;
        this.activity = activity;
        this.concurrency = Math.max(1, concurrency);
        this.slots = new Semaphore(this.concurrency);
        this.enabled = enabled;
        this.seasonConcurrency = Math.max(this.concurrency, seasonConcurrency);
        this.seasonEndDate = seasonEndDate == null ? "" : seasonEndDate.trim();
    }

    /** 分区榜抓取出口：熊猫动态住宅 IP；未启用/拉取失败返回 null（回退 sing-box）。 */
    private com.rankharvester.net.ProxyEndpoint pandaForFetch() {
        return (pandaProxy != null && pandaProxy.enabled()) ? pandaProxy.acquire() : null;
    }

    /**
     * 扫描所有 rankKey × 有号分区，提交抓取。{@code force=false}：静态且已抓过的分区跳过、动态总是抓；
     * {@code force=true}：全部重抓。返回提交数。
     */
    public int fetchAll(boolean force) {
        if (!enabled) {
            return 0;
        }
        lastRunAt = System.currentTimeMillis();
        refreshAccountIndex();
        claimed.clear(); // 新一轮去重
        activity.newWave(); // 进度条：本波重设峰值基线，从 0 重新爬
        Set<String> dynamicKeys = new HashSet<>();
        Map<String, Integer> partitionCounts = new java.util.HashMap<>();
        for (RankSliceStore.KeyInfo k : sliceStore.listKeys()) {
            if (k.dynamic()) {
                dynamicKeys.add(k.rankKey());
            }
            partitionCounts.put(k.rankKey(), k.partitionCount());
        }
        int n = 0;
        // 按「账号×区」分组：一次登录抓该号在各 rankKey 所属的分区榜（登录次数从 ~分区数 降到 ~账号数）。
        for (RankSliceStore.SourceTask src : sliceStore.allSourceTasks()) {
            if (submitSource(src, force, dynamicKeys, partitionCounts)) {
                n++;
            }
        }
        log.info("[PartRank] 提交 {} 个账号来源（每号一次登录抓多 rankKey 分区），并发 {}", n, concurrency);
        return n;
    }

    private void refreshAccountIndex() {
        accountById.clear();
        for (GameAccount a : accounts.all()) {
            accountById.put(a.id(), a);
        }
    }

    private boolean submitSource(RankSliceStore.SourceTask src, boolean force, Set<String> dynamicKeys,
            Map<String, Integer> partitionCounts) {
        String key = src.accountId() + "@" + src.server();
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        pool.submit(() -> {
            // 跳过判断（纯本地：已抓过/已被别号认领→跳过）放在信号量<b>之前</b>：空转来源瞬间完成、不占抓取槽位，
            // 这样进度分母=真实待抓分区数，抓取也不被空转来源拖慢。只有真有活的来源才去登录抓取。
            var tasks = new ArrayList<int[]>(); // [rankKeyInt, partition]
            var rankKeyStrs = new ArrayList<String>();
            buildTasks(src, force, dynamicKeys, partitionCounts, tasks, rankKeyStrs);
            if (tasks.isEmpty()) {
                inFlight.remove(key); // 空转来源：无需登录抓取，直接结束
                return;
            }
            slicePartitions.addAndGet(tasks.size()); // 真实待抓分区计入进度分母
            boolean acquired = false;
            try {
                slots.acquire();
                acquired = true;
                fetchClaimedTasks(src, tasks, rankKeyStrs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseClaims(rankKeyStrs, tasks); // 未抓的认领放回
            } finally {
                slicePartitions.addAndGet(-tasks.size()); // 平衡：本来源退出，从分母移除
                if (acquired) {
                    slots.release();
                }
                inFlight.remove(key);
            }
        });
        return true;
    }

    /** 认领本号要抓的 (rankKey, 分区)（<b>纯本地</b>判断，不登录/不联网）：季末已抓→跳过；静态且已抓过→跳过；已被别号认领→跳过。 */
    private void buildTasks(RankSliceStore.SourceTask src, boolean force, Set<String> dynamicKeys,
            Map<String, Integer> partitionCounts, List<int[]> tasks, List<String> rankKeyStrs) {
        for (var e : src.rankKeyToPartition().entrySet()) {
            String rankKey = e.getKey();
            int partition = e.getValue();
            int partitionCount = partitionCounts.getOrDefault(rankKey, RankSliceStore.MAX_SLICES);
            if (partition < 0 || partition >= partitionCount) {
                continue;
            }
            boolean dynamic = dynamicKeys.contains(rankKey);
            long floor = seasonFreshFloor;
            if (floor > 0 && rankStore.freshAfter(rankKey, partition, floor)) {
                continue; // 季末模式：本轮已抓到该分区，后续波次只补未抓到的
            }
            if (!force && !dynamic) {
                continue; // 静态=不抓（调度只抓动态榜；静态榜只在「一键全抓」force 时抓）。结束的活动改静态即停。
            }
            String dk = rankKey + "#" + partition;
            if (claimed.putIfAbsent(dk, Boolean.TRUE) != null) {
                continue; // 本轮已被别的号认领
            }
            tasks.add(new int[] {parseInt(rankKey), partition});
            rankKeyStrs.add(rankKey);
        }
    }

    /** 已认领的 tasks 非空：登录一次抓这些分区（含 rankType 校验、迟到帧防污染、封号处理）。 */
    private void fetchClaimedTasks(RankSliceStore.SourceTask src, List<int[]> tasks, List<String> rankKeyStrs) {
        GameAccount acct = accountById.get(src.accountId());
        if (acct == null) {
            releaseClaims(rankKeyStrs, tasks);
            return;
        }
        GameConnection conn = null;
        try {
            conn = gameClient.connectAndLogin(acct, src.server(), pandaForFetch()); // 一次登录，抓多个分区榜
            for (int i = 0; i < tasks.size(); i++) {
                int rk = tasks.get(i)[0];
                int partition = tasks.get(i)[1];
                String rankKey = rankKeyStrs.get(i);
                String aid = "p:" + rankKey + "#" + partition + ":" + src.accountId();
                activity.begin(aid, rankKey, partition, true);
                try {
                    var apc = conn.call("getReducedRankByPage", "callbackGetReducedRankByPage",
                            CALL_TIMEOUT, rk, 0, 1000, 0);
                    var params = apc.getParameters();
                    // 回包 parameters[1] = rankType（=请求的 rankKey）。连接层回包只按「回调名」匹配、无
                    // requestId 隔离：某次请求超时后迟到的帧会被同连接的下一次请求错领（典型现象：乱斗数据
                    // 串进副本榜）。一旦发现 rankType 不符，说明该连接已被迟到帧污染，立即放弃整条连接、把
                    // 余下任务释放给别的号用全新连接重抓，杜绝错位。
                    Integer respKey = (params != null && params.size() > 1) ? asInt(params.get(1)) : null;
                    if (respKey == null || respKey.intValue() != rk) {
                        failed.incrementAndGet();
                        log.warn("[PartRank] {}#{} 回包 rankType={} 与请求 {} 不符（连接已被迟到帧污染），"
                                + "弃连接并把余下 {} 个任务放回重抓", rankKey, partition, respKey, rk, tasks.size() - i);
                        releaseClaimsFrom(rankKeyStrs, tasks, i); // 含当前任务在内，全部放回
                        break;
                    }
                    var rows = parser.parse(params);
                    if (rows.isEmpty()) {
                        // 空响应（游戏对该分区返回 null/空榜）不替换：replacePartition 会先 DELETE，
                        // 空写会把上次抓到的榜清空。宁可保留旧数据也不抹成空白。跳过该分区，继续下一个任务。
                        done.incrementAndGet();
                        continue;
                    }
                    rankStore.replacePartition(rankKey, partition, rows, System.currentTimeMillis());
                    rowsWritten.addAndGet(rows.size());
                    subscriptions.notifyPartition(rankKey, partition, rows); // 排名变动→发邮件
                    done.incrementAndGet();
                } catch (RuntimeException e) {
                    // 超时/发送失败：迟到帧可能污染后续请求 → 同样弃连接、释放余下任务换号重抓。
                    failed.incrementAndGet();
                    log.debug("[PartRank] {}#{} 经号 {} 抓取失败，弃连接放回余下 {} 个: {}",
                            rankKey, partition, src.accountId(), tasks.size() - i, e.getMessage());
                    releaseClaimsFrom(rankKeyStrs, tasks, i);
                    break;
                } finally {
                    activity.end(aid);
                }
            }
        } catch (com.rankharvester.net.BannedOnServerException e) {
            // rc=4 封号：放回认领给别的号 + 永久剔除该 (账号×区)，不再用这个封号登录。
            releaseClaims(rankKeyStrs, tasks);
            failed.incrementAndGet();
            accounts.removeServer(src.accountId(), src.server());
            accountById.remove(src.accountId());
            try {
                accountStore.removeServer(Long.parseLong(src.accountId().trim()), src.server());
            } catch (RuntimeException ignore) { /* best-effort */ }
            log.info("[PartRank] {}@{} 封号(rc=4)，已剔除该区", src.accountId(), src.server());
        } catch (RuntimeException e) {
            releaseClaims(rankKeyStrs, tasks); // 登录失败：本号所有认领放回
            failed.incrementAndGet();
            log.debug("[PartRank] 号 {}@{} 登录失败: {}", src.accountId(), src.server(), e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (RuntimeException ignore) { /* best-effort */ }
            }
        }
    }

    private void releaseClaims(List<String> rankKeyStrs, List<int[]> tasks) {
        releaseClaimsFrom(rankKeyStrs, tasks, 0);
    }

    /** 放回 [fromIdx, end) 的认领，供别的号用全新连接重抓。 */
    private void releaseClaimsFrom(List<String> rankKeyStrs, List<int[]> tasks, int fromIdx) {
        for (int i = fromIdx; i < tasks.size(); i++) {
            claimed.remove(rankKeyStrs.get(i) + "#" + tasks.get(i)[1]);
        }
    }

    /** 动态刷新 + 新覆盖分区补抓：每 30 分钟。 */
    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 5 * 60_000L)
    void scheduled() {
        fetchAll(false);
    }

    // ───────────────────────── 季末抢数（最大并发 + 按每榜分区数量补齐） ─────────────────────────

    /** 季末抢数最长跑多久（保底，避免个别分区无号永远抓不到导致死循环）。 */
    private static final long SEASON_DEADLINE_MS = 60 * 60_000L;

    /** 每天 22:03（北京）：若今天==配置的赛季结束日，则启动季末抢数。容器 JVM 为 UTC，故显式指定时区。 */
    @Scheduled(cron = "0 3 22 * * *", zone = "Asia/Shanghai")
    void seasonEndCron() {
        if (seasonEndDate.isEmpty()) {
            return; // 未配置日期：只手动触发
        }
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(); // YYYY-MM-DD
        if (today.equals(seasonEndDate)) {
            log.info("[SeasonEnd] 今天是赛季结束日 {}，22:03 启动季末抢数", seasonEndDate);
            seasonEndFetch();
        }
    }

    /** 每 10 分钟检查各榜结束时间；进入结束前 6 小时即启动抢数，预留足够重试时间。 */
    @Scheduled(fixedDelay = 10 * 60_000L, initialDelay = 2 * 60_000L)
    void seasonDeadlineWatch() {
        if (!enabled || seasonRunning.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (RankSliceStore.KeyInfo k : sliceStore.listKeys()) {
            long end = k.seasonEndAt();
            if (end <= 0) {
                continue;
            }
            if (now >= end - SEASON_PREPARE_MS && now < end) {
                String triggerKey = k.rankKey() + "@" + end;
                if (!autoSeasonTriggered.add(triggerKey)) {
                    continue;
                }
                log.info("[SeasonEnd] {} 将于 {} 结束，提前 {} 小时启动季末抢数",
                        k.rankKey(), end, SEASON_PREPARE_MS / 3600_000L);
                seasonEndFetch();
                return;
            }
        }
    }

    /**
     * 季末抢数：临时把并发拉到 {@link #seasonConcurrency}，<b>循环重试直到所有隐藏榜/配置了结束时间的榜
     * 按各自 partition_count 全部在本轮抓到</b>
     * （或到 {@link #SEASON_DEADLINE_MS} 保底）。异步执行，已在跑则忽略。返回是否启动成功。
     */
    public boolean seasonEndFetch() {
        if (!enabled || !seasonRunning.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("season-end").start(() -> {
            long start = System.currentTimeMillis();
            int boost = Math.max(0, seasonConcurrency - concurrency);
            slots.release(boost);          // 临时加并发（季末爆发）
            seasonFreshFloor = start;      // 进入季末模式：只抓本轮尚未抓到的分区
            int wave = 0;
            try {
                long deadline = start + SEASON_DEADLINE_MS;
                int miss = missingCount(start);
                log.info("[SeasonEnd] 开始：并发 {}，待抓隐藏榜分区 {} 个", seasonConcurrency, miss);
                while (miss > 0 && System.currentTimeMillis() < deadline) {
                    wave++;
                    seasonStatus = "running:第" + wave + "波 缺" + miss;
                    fetchAll(true);            // 提交一波（季末模式下只会认领未抓到的分区）
                    awaitDrain(deadline);      // 等本波所有账号跑完
                    miss = missingCount(start);
                    log.info("[SeasonEnd] 第 {} 波后仍缺 {} 个分区", wave, miss);
                }
                seasonStatus = "done:缺" + miss + " 用" + wave + "波 " + ((System.currentTimeMillis() - start) / 1000) + "s";
                log.info("[SeasonEnd] 结束：{} 波，仍缺 {} 个分区，耗时 {}s", wave, miss, (System.currentTimeMillis() - start) / 1000);
            } catch (RuntimeException e) {
                seasonStatus = "error:" + e.getMessage();
                log.warn("[SeasonEnd] 异常: {}", e.getMessage());
            } finally {
                seasonFreshFloor = 0L;
                slots.acquireUninterruptibly(boost); // 收回临时并发（等正在跑的归还，不被中断打断）
                seasonRunning.set(false);
            }
        });
        return true;
    }

    /** 等本波所有账号任务跑完（inFlight 清空）或到 deadline。 */
    private void awaitDrain(long deadline) {
        // 先给提交一点时间填充 inFlight，再等其清空。
        while (!inFlight.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 所有隐藏榜里「本轮(ts 之后)尚未抓到」的分区总数（0=全齐）。 */
    private int missingCount(long ts) {
        int miss = 0;
        for (RankSliceStore.KeyInfo k : sliceStore.listKeys()) {
            if (!k.hidden() && k.seasonEndAt() <= 0) {
                continue;
            }
            int parts = Math.max(1, Math.min(RankSliceStore.MAX_SLICES, k.partitionCount()));
            if (parts <= 0) {
                continue;
            }
            var fresh = rankStore.freshPartitions(k.rankKey(), ts);
            for (int p = 0; p < parts; p++) {
                if (!fresh.contains(p)) {
                    miss++;
                }
            }
        }
        return miss;
    }

    public String seasonStatus() {
        return seasonStatus;
    }

    /**
     * 即刻抓取：对某 rankKey 的所有已知分区各取一个号抓一次（按分区去重，避免重复登录），
     * 全部完成后给 {@code email} 发完成通知邮件。返回提交的分区数（0=该榜暂无可抓来源）。
     * 走调度 {@link #slots} 限流（默认并发 6）。
     */
    public int fetchRankKeyNow(String rankKey, String email) {
        return submitRankKeyFetch(rankKey, email, slots);
    }

    /**
     * 后台「一键全抓」：抓某 rankKey 的全部配置分区，不发邮件。
     * 返回提交的分区数（0=该榜暂无可抓来源）。登录压力大，仅供后台手动触发。
     */
    public int fetchRankKeyNowFast(String rankKey) {
        return submitRankKeyFetch(rankKey, null, slots);
    }

    /** 整榜即刻抓取公共实现：按分区去重各取一个号，用 {@code sem} 限流并发；全部完成后（email 非空）发通知。 */
    private int submitRankKeyFetch(String rankKey, String email, Semaphore sem) {
        if (!enabled) {
            return 0;
        }
        int rk = parseInt(rankKey);
        if (rk <= 0) {
            return 0;
        }
        refreshAccountIndex();
        // 按分区去重：每个分区挑一个来源号，避免同分区被多号重复抓。
        var byPartition = new java.util.LinkedHashMap<Integer, RankSliceStore.SourceTask>();
        int partitionCount = sliceStore.listKeys().stream()
                .filter(k -> k.rankKey().equals(rankKey))
                .findFirst()
                .map(RankSliceStore.KeyInfo::partitionCount)
                .orElse(RankSliceStore.MAX_SLICES);
        for (var src : sliceStore.allSourceTasks()) {
            Integer p = src.rankKeyToPartition().get(rankKey);
            if (p != null && p >= 0 && p < partitionCount) {
                byPartition.putIfAbsent(p, src);
            }
        }
        if (byPartition.isEmpty()) {
            return 0;
        }
        int totalParts = byPartition.size();
        immediatePending.addAndGet(totalParts); // 进度条分母：本批待办分区数
        var remaining = new AtomicInteger(totalParts);
        var okParts = new AtomicLong();
        var rowsAgg = new AtomicLong();
        for (var e : byPartition.entrySet()) {
            int partition = e.getKey();
            RankSliceStore.SourceTask src = e.getValue();
            pool.submit(() -> {
                boolean acquired = false;
                try {
                    sem.acquire();
                    acquired = true;
                    int n = fetchSingle(src, rk, rankKey, partition);
                    if (n >= 0) {
                        okParts.incrementAndGet();
                        rowsAgg.addAndGet(n);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    immediatePending.decrementAndGet();
                    if (acquired) {
                        sem.release();
                    }
                    if (remaining.decrementAndGet() == 0) {
                        finishNow(email, rankKey, totalParts, okParts.get(), rowsAgg.get());
                    }
                }
            });
        }
        log.info("[PartRank] 即刻抓取 {} 提交 {} 个分区，完成后通知 {}", rankKey, totalParts, email);
        return totalParts;
    }

    /**
     * 即刻抓取<b>单个分区</b>：挑一个覆盖该 (rankKey, 分区) 的来源号登录抓这一个分区。异步提交。
     * 返回 true=已提交（该分区有可用来源号），false=该分区暂无号→抓不了。
     */
    public boolean fetchPartitionNow(String rankKey, int partition) {
        if (!enabled) {
            return false;
        }
        int rk = parseInt(rankKey);
        if (rk <= 0) {
            return false;
        }
        refreshAccountIndex();
        int partitionCount = sliceStore.listKeys().stream()
                .filter(k -> k.rankKey().equals(rankKey))
                .findFirst()
                .map(RankSliceStore.KeyInfo::partitionCount)
                .orElse(RankSliceStore.MAX_SLICES);
        if (partition < 0 || partition >= partitionCount) {
            return false;
        }
        // 收集覆盖该 (rankKey, 分区) 的多个来源号，登录失败就换下一个号(各自换新熊猫 IP)重试，最多试 PART_FETCH_MAX_TRY 个。
        var srcs = new java.util.ArrayList<RankSliceStore.SourceTask>();
        for (var s : sliceStore.allSourceTasks()) {
            Integer p = s.rankKeyToPartition().get(rankKey);
            if (p != null && p == partition) {
                srcs.add(s);
                if (srcs.size() >= PART_FETCH_MAX_TRY) {
                    break;
                }
            }
        }
        if (srcs.isEmpty()) {
            return false; // 该分区没有覆盖它的来源号
        }
        immediatePending.incrementAndGet(); // 进度条分母：本单区待办
        pool.submit(() -> {
            boolean acquired = false;
            try {
                slots.acquire();
                acquired = true;
                for (var s : srcs) {
                    int n = fetchSingle(s, rk, rankKey, partition);
                    if (n >= 0) {
                        break; // n>0=抓到数据；n=0=游戏确认该分区为空(再换号也没用)；都停。n<0=登录/抓取失败→换下一个号重试
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                immediatePending.decrementAndGet();
                if (acquired) {
                    slots.release();
                }
            }
        });
        log.info("[PartRank] 即刻抓取单分区 {}#{} 已提交（登录失败最多换 {} 个号重试）", rankKey, partition, srcs.size());
        return true;
    }

    private void finishNow(String email, String rankKey, int totalParts, long okParts, long rows) {
        if (email == null || email.isBlank()) {
            return; // 内部调用可不带邮箱：不发完成通知
        }
        try {
            subscriptions.sendFetchDone(email, rankKey, totalParts, (int) okParts, rows);
        } catch (RuntimeException e) {
            log.warn("[PartRank] 即刻抓取完成邮件发送失败 {}: {}", email, e.getMessage());
        }
    }

    /** 登录一个号抓单个 rankKey/分区（含 rankType 校验）。成功返回行数，失败/错配返回 -1。 */
    private int fetchSingle(RankSliceStore.SourceTask src, int rk, String rankKey, int partition) {
        GameAccount acct = accountById.get(src.accountId());
        if (acct == null) {
            return -1;
        }
        String aid = "s:" + rankKey + "#" + partition + ":" + src.accountId();
        activity.begin(aid, rankKey, partition, true);
        GameConnection conn = null;
        com.rankharvester.net.ProxyEndpoint proxy = pandaForFetch();
        boolean loggedIn = false;
        try {
            conn = gameClient.connectAndLogin(acct, src.server(), proxy);
            loggedIn = true;
            if (pandaProxy != null) {
                pandaProxy.reportLoginSuccess(proxy); // 该 IP 能登录，清零三振计数
            }
            var apc = conn.call("getReducedRankByPage", "callbackGetReducedRankByPage",
                    CALL_TIMEOUT, rk, 0, 1000, 0);
            var params = apc.getParameters();
            Integer respKey = (params != null && params.size() > 1) ? asInt(params.get(1)) : null;
            if (respKey == null || respKey.intValue() != rk) {
                failed.incrementAndGet();
                return -1; // 错配帧，不写库
            }
            var rows = parser.parse(params);
            // 空响应（游戏对该分区返回 null/空榜）不替换：replacePartition 会先 DELETE，空写会把上次抓到的榜清空。
            // 排行榜玩家不会凭空消失，宁可保留旧数据也不抹成空白。返回 0=本次无新数据但不算失败。
            if (rows.isEmpty()) {
                done.incrementAndGet();
                return 0;
            }
            rankStore.replacePartition(rankKey, partition, rows, System.currentTimeMillis());
            rowsWritten.addAndGet(rows.size());
            subscriptions.notifyPartition(rankKey, partition, rows);
            done.incrementAndGet();
            return rows.size();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            if (!loggedIn && pandaProxy != null) {
                pandaProxy.reportLoginFailure(proxy); // 登录失败多半是慢/坏IP(ping超时)：同一IP累计3次拉黑
            }
            return -1;
        } finally {
            activity.end(aid);
            if (conn != null) {
                try { conn.close(); } catch (RuntimeException ignore) { /* best-effort */ }
            }
        }
    }

    /**
     * 本波分区榜<b>尚未完成</b>的待办量，供全局进度条做分母（单位=分区）：
     * 调度路径 = {@code slicePartitions}（真实待抓分区数，已扣除"已抓过/被认领"的空转来源）+
     * 即刻路径 = {@code immediatePending}（即刻抓取已提交未完成的分区数）。
     */
    public int outstandingWork() {
        return Math.max(0, slicePartitions.get()) + Math.max(0, immediatePending.get());
    }

    public Map<String, Object> status() {
        return Map.of(
                "totalRows", rankStore.count(),
                "done", done.get(),
                "failed", failed.get(),
                "rowsWritten", rowsWritten.get(),
                "inFlight", inFlight.size(),
                "concurrency", concurrency,
                "lastRunAt", lastRunAt);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
