package com.rankharvester.rank.fetch;

import com.rankharvester.net.GameConnection;
import com.rankharvester.net.GameLoginService;
import com.rankharvester.rank.model.FetchTask;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.queue.RedisTaskQueue;
import com.rankharvester.rank.scheduler.RankStarvationScheduler;
import com.rankharvester.rank.store.LeaderboardCatalog;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 抓取编排器（消费者）。
 *
 * <p>每个 tick：饥饿调度器领取一批任务 → 按 <b>大区</b> 分组 → 每区经 {@link GameLoginService}
 * 登录一次（代理 + 失败转移 + rc=4 剔除该区换号）→ 在同一连接上串行抓该区的多个榜
 * （{@link RankFetchRouter} 按榜形解析落库）→ ack；失败按退避重入 RETRY lane。
 * 连接级并发由信号量限制（TCP 连接数上界）。
 */
@Component
public class RankFetchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RankFetchOrchestrator.class);

    private final RankStarvationScheduler scheduler;
    private final RedisTaskQueue queue;
    private final LeaderboardCatalog catalog;
    private final GameLoginService loginService;
    private final RankFetchRouter router;
    private final FetchProperties props;
    private final com.rankharvester.rank.sub.PlayerChangeService playerChangeService;
    private final FetchActivityService activity;

    private final ExecutorService groupExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Semaphore connectionPermits;

    public RankFetchOrchestrator(
            RankStarvationScheduler scheduler,
            RedisTaskQueue queue,
            LeaderboardCatalog catalog,
            GameLoginService loginService,
            RankFetchRouter router,
            FetchProperties props,
            com.rankharvester.rank.sub.PlayerChangeService playerChangeService,
            FetchActivityService activity) {
        this.scheduler = scheduler;
        this.queue = queue;
        this.catalog = catalog;
        this.loginService = loginService;
        this.router = router;
        this.props = props;
        this.playerChangeService = playerChangeService;
        this.activity = activity;
    }

    @Scheduled(fixedDelayString = "${rankharvester.fetch.tick-ms:2000}")
    public void tick() {
        long now = System.currentTimeMillis();
        var claimed = scheduler.claimBatch(now);
        if (claimed.isEmpty()) {
            return;
        }

        var groups = groupByServer(claimed);
        var futures = new ArrayList<Future<?>>(groups.size());
        for (var entry : groups.entrySet()) {
            var tasks = entry.getValue();
            futures.add(groupExecutor.submit(() -> processServer(entry.getKey(), tasks)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.warn("分组抓取异常: {}", e.toString());
            }
        }
    }

    /** 按大区分组（同区多个榜复用一条 TCP 连接）。 */
    private Map<String, List<FetchTask>> groupByServer(List<FetchTask> claimed) {
        var groups = new LinkedHashMap<String, List<FetchTask>>();
        for (FetchTask t : claimed) {
            var def = catalog.byCode(t.leaderboardCode()).orElse(null);
            if (def == null) {
                queue.ack(t.id()); // 孤儿任务直接丢弃
                continue;
            }
            groups.computeIfAbsent(def.server(), k -> new ArrayList<>()).add(t);
        }
        return groups;
    }

    private void processServer(String server, List<FetchTask> tasks) {
        var permits = connectionPermits();
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            for (FetchTask task : tasks) {
                var def = catalog.byCode(task.leaderboardCode()).orElse(null);
                if (def == null) {
                    queue.ack(task.id());
                    continue;
                }
                fetchOne(server, def, task);
            }
        } finally {
            permits.release();
        }
    }

    /** 单榜抓取最多尝试的 IP 个数：某页超时即丢掉慢 IP、换新 IP 从断点续抓；用尽仍未完成则退避（保留断点下轮续）。 */
    private static final int MAX_IP_SWITCH = 8;

    /**
     * 抓单个榜：分页 + 断点续传 + <b>换 IP 续抓</b>。某页经慢/坏住宅 IP 超时 → 丢弃该连接、借新连接（轮询下一个熊猫 IP）
     * → {@link RankFetchRouter} 从 rank_progress 检查点续抓（不重头）。最多换 {@link #MAX_IP_SWITCH} 个 IP。
     */
    private void fetchOne(String server, LeaderboardDef def, FetchTask task) {
        var pageTimeout = Duration.ofSeconds(props.getPageTimeoutSeconds());
        String aid = "m:" + def.code();
        activity.begin(aid, def.code(), null, false);
        GameConnection conn = null;
        try {
            for (int ipTry = 1; ipTry <= MAX_IP_SWITCH; ipTry++) {
                try {
                    conn = loginService.borrow(server); // 池空才现登；现登时轮询拿下一个熊猫 IP
                } catch (RuntimeException loginErr) {
                    long retryAt = System.currentTimeMillis() + 60_000L + task.attempts() * 30_000L;
                    queue.fail(task.id(), def.code(), retryAt);
                    log.warn("[{}] 登录失败，退避重试: {}", def.code(), loginErr.getMessage());
                    return;
                }
                try {
                    int rows = router.fetchAndStore(conn, def, pageTimeout);
                    queue.ack(task.id());
                    loginService.release(server, conn); // 成功：归还池复用
                    conn = null;
                    log.info("[{}] 抓取完成 {} 行（用了 {} 个 IP）", def.code(), rows, ipTry);
                    // 同服战力榜刷新后，比对玩家变化订阅（改名/VIP/境界），变化即发邮件。
                    if (def.code().startsWith("REDUCED:382:")) {
                        try {
                            playerChangeService.checkServer(def.server());
                        } catch (RuntimeException e) {
                            log.warn("[{}] 玩家变化订阅比对失败: {}", def.code(), e.getMessage());
                        }
                    }
                    return;
                } catch (RuntimeException fetchErr) {
                    closeQuietly(conn); // 慢/坏 IP 连接弃用，不回池
                    conn = null;
                    log.info("[{}] 第 {} 个 IP 抓取中断，换 IP 从断点续抓: {}", def.code(), ipTry, fetchErr.getMessage());
                }
            }
            // 换满 MAX_IP_SWITCH 个 IP 仍未抓完 → 退避重试（检查点保留，下轮从断点继续）
            long retryAt = System.currentTimeMillis() + 60_000L + task.attempts() * 30_000L;
            queue.fail(task.id(), def.code(), retryAt);
            log.warn("[{}] 换了 {} 个 IP 仍未抓完，退避重试（保留断点）", def.code(), MAX_IP_SWITCH);
        } finally {
            activity.end(aid);
            if (conn != null) {
                closeQuietly(conn);
            }
        }
    }

    private static void closeQuietly(GameConnection conn) {
        try {
            conn.close();
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    private Semaphore connectionPermits() {
        var p = connectionPermits;
        if (p == null) {
            p = new Semaphore(Math.max(1, props.getAccountConcurrency()));
            connectionPermits = p;
        }
        return p;
    }

    @PreDestroy
    void shutdown() {
        groupExecutor.shutdownNow();
    }
}
