package com.rankharvester.rank.slice;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.account.GameAccountStore;
import com.rankharvester.net.BannedOnServerException;
import com.rankharvester.net.GameClient;
import com.rankharvester.net.GameConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 「分区获取」后台抓取：<b>并发</b>遍历号池所有账号 × 其所有区，逐个 {@code getRankSliceGroupMap}
 * 拿到 {@code {rankKey: 分片数}} 落库。新出现的 rankKey 触发全量重抓（所有账号重抓一遍）。
 *
 * <p>并发用一个固定大小的虚拟线程 + 信号量限流（默认 12 并发，可配），3000+ 账号也能较快铺完。
 * 进度条：{@code target}=总 (账号×区) 数，{@code done}=已采集来源数。
 */
@Service
public class RankSliceFetchService {

    private static final Logger log = LoggerFactory.getLogger(RankSliceFetchService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(25);
    /** 单个 (账号×区) 最多重试次数；超过判定为「该号在该区无效」（如号不在此大区），不再无限重试硬扛服务器。 */
    private static final int MAX_ATTEMPTS = 2; // 少试几次就放弃死/无效组合，减少登录次数（过度访问会被游戏封号 rc=4）

    private final GameClient gameClient;
    private final com.rankharvester.net.PandaProxyService pandaProxy;
    private final AccountRegistry accounts;
    private final RankSliceStore store;
    private final GameAccountStore accountStore;
    private final int concurrency;
    private final boolean enabled;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final ConcurrentHashMap<String, GameAccount> accountById = new ConcurrentHashMap<>();
    /** 已提交但未完成的去重集（key=accountId@server），避免重复/风暴。 */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    /** 每个 (账号×区) 的失败次数；达到 MAX_ATTEMPTS 即放弃（断点续传时跳过死号），避免无限重试。 */
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();
    /** 抓到的结果先入此队列，由单写线程定时批量落库（避免并发抢 DuckDB 单连接饿死读请求）。 */
    private final ConcurrentLinkedQueue<RankSliceStore.Source> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile long lastFullEnqueueAt = 0L;

    public RankSliceFetchService(
            GameClient gameClient,
            com.rankharvester.net.PandaProxyService pandaProxy,
            AccountRegistry accounts,
            RankSliceStore store,
            GameAccountStore accountStore,
            @Value("${rankharvester.slice.fetch-concurrency:6}") int concurrency,
            @Value("${rankharvester.slice.fetch-enabled:true}") boolean enabled) {
        this.gameClient = gameClient;
        this.pandaProxy = pandaProxy;
        this.accounts = accounts;
        this.store = store;
        this.accountStore = accountStore;
        this.concurrency = Math.max(1, concurrency);
        this.slots = new Semaphore(this.concurrency);
        this.enabled = enabled;
    }

    /** slice 榜抓取出口：熊猫动态住宅 IP；未启用/拉取失败返回 null（回退 sing-box）。 */
    private com.rankharvester.net.ProxyEndpoint pandaForFetch() {
        return (pandaProxy != null && pandaProxy.enabled()) ? pandaProxy.acquire() : null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) {
            log.info("[Slice] 分区获取已禁用");
            return;
        }
        enqueueUncovered();
    }

    /** 总目标 (账号×区) 数（进度条分母）。 */
    public long target() {
        long t = 0;
        for (GameAccount a : accounts.all()) {
            if (a.enabled() && a.servers() != null) {
                t += a.servers().size();
            }
        }
        return t;
    }

    /**
     * 把「尚未采集 或 在最新 rankKey 出现后还没重探过」的 (账号×区) 并发提交。
     * <p>关键：只跳过「已在最新 rankKey 出现<b>之后</b>采集过」的来源——它们的 map 已含新榜。已采集但 map 早于
     * 新榜的来源会被重探，否则新 rankKey 永远铺不到老账号覆盖的分区（覆盖会卡死，等多久都不动）。
     * 失败达 {@link #MAX_ATTEMPTS} 次的死号放弃，不无限硬扛。
     */
    public int enqueueUncovered() {
        refreshAccountIndex();
        long epoch = store.newestKeyFirstSeen(); // 最新 rankKey 首次发现时间：早于它采集的来源 map 可能漏掉新榜，需重探
        int n = 0;
        int skippedDead = 0;
        for (GameAccount a : accounts.all()) {
            if (!a.enabled() || a.servers() == null) {
                continue;
            }
            for (String server : a.servers()) {
                if (store.hasFreshSource(a.id(), server, epoch)) {
                    continue; // 新榜出现后已重探过，map 最新，跳过
                }
                if (attempts.getOrDefault(a.id() + "@" + server, 0) >= MAX_ATTEMPTS) {
                    skippedDead++;
                    continue; // 多次失败，判定该号在该区无效，放弃
                }
                if (submit(a.id(), server)) {
                    n++;
                }
            }
        }
        log.info("[Slice] 续传/补新榜提交 {} 个 (账号×区)（已放弃 {} 个多次失败的），并发 {}", n, skippedDead, concurrency);
        return n;
    }

    /**
     * 主动续传：每 5 分钟重提交尚未覆盖的 (账号×区)，把上一轮登录失败/超时的号再试一遍，
     * 让分区覆盖持续向「号池真实能触达的分区上限」逼近，而不是停在首轮成功数。
     * 失败达 {@link #MAX_ATTEMPTS} 次的死号会被跳过，不会无限硬扛服务器。
     */
    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 5 * 60_000L)
    void resumeUncovered() {
        if (!enabled) {
            return;
        }
        enqueueUncovered();
    }

    /** 全量重提交（新 rankKey / 手动）。5 分钟内不重复，避免风暴。 */
    public int enqueueAll() {
        long now = System.currentTimeMillis();
        if (now - lastFullEnqueueAt < 5 * 60_000L) {
            return 0;
        }
        lastFullEnqueueAt = now;
        refreshAccountIndex();
        int n = 0;
        for (GameAccount a : accounts.all()) {
            if (!a.enabled() || a.servers() == null) {
                continue;
            }
            for (String server : a.servers()) {
                if (submit(a.id(), server)) {
                    n++;
                }
            }
        }
        log.info("[Slice] 全量重提交 {} 个 (账号×区)", n);
        return n;
    }

    /** rc=4 封号：内存 + DB 都剔除该 (账号×区)，让续传/全量都不再选中它。 */
    private void removeBannedServer(String accountId, String server) {
        accounts.removeServer(accountId, server);
        accountById.remove(accountId); // 下轮 refreshAccountIndex 会按新 servers 重建
        try {
            accountStore.removeServer(Long.parseLong(accountId.trim()), server);
        } catch (RuntimeException ignore) {
            // best-effort：DB 持久化失败不影响本轮剔除
        }
        log.info("[Slice] {}@{} 封号(rc=4)，已剔除该区不再重试", accountId, server);
    }

    private void refreshAccountIndex() {
        accountById.clear();
        for (GameAccount a : accounts.all()) {
            accountById.put(a.id(), a);
        }
    }

    private boolean submit(String accountId, String server) {
        String k = accountId + "@" + server;
        if (inFlight.putIfAbsent(k, Boolean.TRUE) != null) {
            return false; // 已在队列/进行中
        }
        submitted.incrementAndGet();
        active.set(true);
        pool.submit(() -> {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                inFlight.remove(k);
                return;
            }
            try {
                fetchOne(accountId, server);
            } finally {
                slots.release();
                inFlight.remove(k);
                if (inFlight.isEmpty()) {
                    active.set(false);
                }
            }
        });
        return true;
    }

    private void fetchOne(String accountId, String server) {
        GameConnection conn = null;
        // 本次采集用到的熊猫出口 IP：成功清零其三振计数、失败累计；坏/慢 IP(ping超时) 累计 3 次即被拉黑，
        // 后续采集不再抽到它——这样发现覆盖才能突破「卡在能用 IP 上」的瓶颈（新 rankKey 才铺得开）。
        com.rankharvester.net.ProxyEndpoint proxy = pandaForFetch();
        try {
            GameAccount acct = accountById.get(accountId);
            if (acct == null) {
                return;
            }
            conn = gameClient.connectAndLogin(acct, server, proxy);
            var apc = conn.call("getRankSliceGroupMap", "callbackGetRankSliceGroupMap", CALL_TIMEOUT);
            Map<String, Integer> map = parseSliceMap(apc.getParameters());
            if (!map.isEmpty()) {
                pending.add(new RankSliceStore.Source(accountId, server, map)); // 入队，单写线程批量落库
            }
            attempts.remove(accountId + "@" + server); // 成功：清零失败计数
            done.incrementAndGet();
            if (pandaProxy != null) {
                pandaProxy.reportLoginSuccess(proxy); // 该 IP 能用，清零三振计数
            }
        } catch (BannedOnServerException e) {
            // rc=4 = 该号在该区被封：永久剔除该 (账号×区)，绝不再试（否则反复登录封号会越封越多）。封号是账号问题，与 IP 无关。
            failed.incrementAndGet();
            attempts.put(accountId + "@" + server, MAX_ATTEMPTS); // 标死，续传跳过
            removeBannedServer(accountId, server);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            attempts.merge(accountId + "@" + server, 1, Integer::sum); // 记一次失败，供续传判定是否放弃
            if (pandaProxy != null) {
                pandaProxy.reportLoginFailure(proxy); // 多半是慢/坏住宅 IP(ping超时)：同一 IP 累计 3 次即拉黑
            }
            log.debug("[Slice] {}@{} 采集失败: {}", accountId, server, e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (RuntimeException ignore) {
                    // best-effort
                }
            }
        }
    }

    /** 单写线程：每 1.5s 把 pending 批量落库（一次事务），新 rankKey 触发全量重抓。 */
    @Scheduled(fixedDelay = 1500L)
    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        var batch = new ArrayList<RankSliceStore.Source>();
        RankSliceStore.Source s;
        while (batch.size() < 1000 && (s = pending.poll()) != null) {
            batch.add(s);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            var newKeys = store.recordBatch(batch);
            if (!newKeys.isEmpty()) {
                enqueueAll(); // 智能：新 rankKey → 所有账号重抓（内部 5 分钟去抖）
            }
        } catch (RuntimeException e) {
            log.warn("[Slice] 批量落库失败({}条): {}", batch.size(), e.getMessage());
        }
    }

    /** callbackGetRankSliceGroupMap：parameters[0] 为 {rankKey: 分片数} 映射对象。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> parseSliceMap(List<Object> params) {
        var out = new java.util.LinkedHashMap<String, Integer>();
        if (params == null || params.isEmpty()) {
            return out;
        }
        Object first = params.get(0);
        if (first instanceof Map<?, ?> m) {
            for (var e : ((Map<String, Object>) m).entrySet()) {
                String k = e.getKey();
                if (k == null || k.startsWith("__")) {
                    continue;
                }
                out.put(k, toInt(e.getValue()));
            }
        } else if (first instanceof JsonNode node && node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext();) {
                var e = it.next();
                if (e.getKey().startsWith("__")) {
                    continue;
                }
                out.put(e.getKey(), e.getValue().asInt(0));
            }
        }
        return out;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    public Map<String, Object> status() {
        long target = target();
        long sources = store.progress().sources();
        return Map.of(
                "target", target,
                "covered", sources,
                "percent", target > 0 ? Math.min(100, Math.round(sources * 1000.0 / target) / 10.0) : 0,
                "submitted", submitted.get(),
                "done", done.get(),
                "failed", failed.get(),
                "concurrency", concurrency,
                "active", active.get());
    }
}
