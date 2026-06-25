package com.rankharvester.account;

import com.rankharvester.net.GameServer;
import com.rankharvester.net.ScoutConnectionService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 侦察号后台管理：增删改查 + 启停 + <strong>全区探测</strong>，写库后<strong>热重载</strong>注册表，运行期生效（无需重启）。
 *
 * <p><b>探测并发模型</b>（仿抓榜 RankSliceFetchService）：所有探测登录（不论哪个号、哪个区）都过<strong>同一个进程级
 * 信号量</strong> {@link #probeSlots}（默认 45，几个登录共用一个熊猫 IP），在共享虚拟线程池上跑。这样无论一次导入多少号，同时在飞的探测登录
 * 恒定 ≤ 并发上限，不会嵌套线程池相乘打爆熊猫代理池 / 触发限频；账号超量只是排队。
 *
 * <p>批量导入 {@link #importBatch} <strong>异步</strong>：立即建号入库返回，探测后台跑、探完<strong>统一 reload 一次</strong>
 * （避免每号一次 reload 的 O(N²)）。单个 {@link #save} 同步（1 号 ~10s 可接受）。
 */
@Service
public class ScoutAccountService {

    private static final Logger log = LoggerFactory.getLogger(ScoutAccountService.class);

    private final ScoutAccountStore store;
    private final ScoutAccountRegistry registry;
    private final ScoutLevelStore levelStore;
    private final ScoutConnectionService scout;

    /** 全局探测并发闸：同时在飞的探测登录 ≤ 此数（默认 45，几个登录共用一个熊猫 IP；可配 rankharvester.scout.probe-concurrency）。 */
    private final Semaphore probeSlots;
    /** 探测任务共享池（虚拟线程，任务再多也只占少量内核线程）。 */
    private final ExecutorService probePool = Executors.newVirtualThreadPerTaskExecutor();
    /** 正在探测中的号（前端区分「探测中」与「探完真无区」）。 */
    private final java.util.Set<Long> probing = ConcurrentHashMap.newKeySet();

    public ScoutAccountService(ScoutAccountStore store, ScoutAccountRegistry registry, ScoutLevelStore levelStore,
            ScoutConnectionService scout,
            @Value("${rankharvester.scout.probe-concurrency:45}") int probeConcurrency) {
        this.store = store;
        this.registry = registry;
        this.levelStore = levelStore;
        this.scout = scout;
        this.probeSlots = new Semaphore(Math.max(1, probeConcurrency), true);
    }

    @PreDestroy
    void shutdown() {
        probePool.shutdownNow();
    }

    public List<ScoutAccountStore.Row> list() {
        return store.all();
    }

    /** 某号是否正在探测中（前端列表展示用）。 */
    public boolean isProbing(long id) {
        return probing.contains(id);
    }

    /**
     * 新增（id 为 null）或更新（id 非 null）一条侦察号，<strong>保存后自动全区探测</strong>（同步，区由探测决定）。
     * 返回该号 id + 探测结果。无 pauth 则跳过探测（区为空，待补 pauth 后重探）。
     */
    public ProbeResult save(Long id, String account, String password, String pauth, String uid) {
        long savedId;
        if (id == null) {
            savedId = store.create(account, password, pauth, uid, List.of(), null, true);
        } else {
            store.update(id, account, password, pauth, uid, List.of(), null, true);
            savedId = id;
        }
        refresh();
        if (pauth == null || pauth.isBlank()) {
            return new ProbeResult(List.of(), Map.of()); // 无 pauth：不探测
        }
        ProbeResult r = probeOne(savedId); // 写库（不 reload）
        refresh();                         // 单号探完 reload 一次
        return r;
    }

    /**
     * 批量导入（<strong>异步</strong>）：立即逐条建号入库 + reload（号在列表可见、标记探测中），探测在后台共享池跑，
     * 探完<strong>统一 reload 一次</strong>。立即返回已建号数；前端轮询 {@link #list}/{@link #isProbing} 看进度。
     */
    public int importBatch(List<Map<String, String>> items) {
        var newIds = new ArrayList<Long>();
        for (var it : items) {
            String account = trim(it.get("account"));
            if (account.isEmpty()) {
                continue;
            }
            long newId = store.create(account, trim(it.get("password")), trim(it.get("pauth")),
                    trim(it.get("uid")), List.of(), null, true);
            newIds.add(newId);
            probing.add(newId); // 先标记探测中
        }
        refresh(); // 入库可见
        if (newIds.isEmpty()) {
            return 0;
        }
        // 后台：所有号并发探测（统一过 probeSlots 信号量，总并发受控），全部探完 reload 一次
        probePool.submit(() -> {
            try {
                var futures = new ArrayList<Future<?>>();
                for (long pid : newIds) {
                    futures.add(probePool.submit(() -> {
                        try {
                            probeOne(pid);
                        } finally {
                            probing.remove(pid);
                        }
                    }));
                }
                for (var f : futures) {
                    try { f.get(); } catch (Exception ignore) { }
                }
            } finally {
                newIds.forEach(probing::remove);
                refresh(); // 统一热重载一次
                log.info("[scout] 批量导入 {} 个号探测完成，已热重载", newIds.size());
            }
        });
        return newIds.size();
    }

    public void delete(long id) {
        store.delete(id);
        refresh();
    }

    public void setEnabled(long id, boolean enabled) {
        store.setEnabled(id, enabled);
        refresh();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 探测一个号能登的区 + 每区等级（返回结果）。 */
    public record ProbeResult(List<String> districts, Map<String, Integer> levels) {}

    /**
     * 全区探测（公开：「重新探测」单号按钮用）：探测 + 写库 + reload。
     */
    public ProbeResult probeDistricts(long id) {
        probing.add(id);
        try {
            ProbeResult r = probeOne(id);
            refresh();
            return r;
        } finally {
            probing.remove(id);
        }
    }

    /**
     * 探测一个号：对全部 {@link GameServer} 区试登录，<strong>每个区任务过全局 {@link #probeSlots} 信号量</strong>
     * （总并发受控），能登成功的区 = 有角色的区，顺手抓每区等级（写缓存+回写表）。结果写回 districts。<strong>不 reload</strong>。
     */
    private ProbeResult probeOne(long id) {
        ScoutAccountStore.Row row = store.all().stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("侦察号不存在: " + id));
        if (row.pauth() == null || row.pauth().isBlank()) {
            throw new IllegalStateException("该号无 pauth，无法探测");
        }
        var servers = GameServer.values();
        var futures = new LinkedHashMap<GameServer, Future<Integer>>();
        for (GameServer g : servers) {
            String loginId = String.valueOf(g.id());
            var acct = new GameAccount(
                    "scout-" + id, row.account(), row.password(), List.of(loginId),
                    row.pauth(), blankToNull(row.uid()), true, true);
            futures.put(g, probePool.submit(() -> {
                probeSlots.acquire(); // 全局并发闸：超量则排队
                try {
                    return scout.probeRegion(acct, loginId);
                } finally {
                    probeSlots.release();
                }
            }));
        }
        var districts = new ArrayList<String>();
        var levels = new LinkedHashMap<String, Integer>();
        for (var e : futures.entrySet()) {
            int lv = awaitQuietly(e.getValue());
            if (lv >= 0) { // 登录成功 = 该区有角色
                String district = String.valueOf(e.getKey().districtId());
                districts.add(district);
                levels.put(district, lv);
            }
        }
        store.setDistricts(id, districts); // 探测结果写回区列表（不 reload，调用方决定何时 reload）
        log.info("[scout {}] 全区探测完成：{} 个区可用 {}", id, districts.size(), levels);
        return new ProbeResult(districts, levels);
    }

    private static int awaitQuietly(Future<Integer> f) {
        try {
            Integer v = f.get(60, TimeUnit.SECONDS);
            return v == null ? -1 : v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 热重载号池并重灌手填等级缓存（按每区灌进 ScoutLevelStore）。 */
    private void refresh() {
        registry.reload();
        for (var seed : registry.configLevelSeeds()) {
            for (String district : seed.districts()) {
                levelStore.put(seed.accountId(), district, seed.level());
            }
        }
    }
}
