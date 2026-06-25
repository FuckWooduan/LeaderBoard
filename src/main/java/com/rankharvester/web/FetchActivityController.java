package com.rankharvester.web;

import com.rankharvester.net.GameServer;
import com.rankharvester.rank.fetch.FetchActivityService;
import com.rankharvester.rank.model.Lane;
import com.rankharvester.rank.queue.RedisTaskQueue;
import com.rankharvester.rank.slice.PartitionRankFetchService;
import com.rankharvester.rank.slice.RankSliceStore;
import com.rankharvester.rank.store.LeaderboardCatalog;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局抓取活动（前端进度条）：{@code GET /api/public/fetch-activity}。
 * 返回当前正在抓取的榜/分区 + 智能进度百分比。分区榜显示「名称 分区N」，主榜显示「类型 · 区服」。
 */
@RestController
public class FetchActivityController {

    private final FetchActivityService activity;
    private final RankSliceStore sliceStore;
    private final LeaderboardCatalog catalog;
    private final RedisTaskQueue queue;
    private final PartitionRankFetchService partitionFetch;

    /** 分区榜名缓存（rankKey→名），3 秒刷新一次，避免每次轮询都查库。 */
    private volatile Map<String, String> nameCache = Map.of();
    private volatile long nameCacheAt = 0L;

    public FetchActivityController(FetchActivityService activity, RankSliceStore sliceStore,
            LeaderboardCatalog catalog, RedisTaskQueue queue, PartitionRankFetchService partitionFetch) {
        this.activity = activity;
        this.sliceStore = sliceStore;
        this.catalog = catalog;
        this.queue = queue;
        this.partitionFetch = partitionFetch;
    }

    @GetMapping("/api/public/fetch-activity")
    public Map<String, Object> fetchActivity() {
        Map<String, String> names = sliceNames();
        return activity.toMap(it -> {
            if (it.slice()) {
                String name = names.getOrDefault(it.rankKey(), it.rankKey());
                return name + (it.partition() == null ? "" : " 分区" + (it.partition() + 1));
            }
            return mainLabel(it.rankKey());
        }, remainingWork());
    }

    /**
     * 本波<b>尚未完成</b>的待办总量，作进度条分母（与已完成数共同决定百分比）：
     * 普通榜 = 各 lane 现已到期任务({@code dueCount}) + 在租约中(已领未完，{@code leaseSize})；
     * 分区榜 = 在途来源 + 即刻待办（{@link PartitionRankFetchService#outstandingWork()}）。
     */
    private long remainingWork() {
        long now = System.currentTimeMillis();
        long regular;
        try {
            long due = 0L;
            for (Lane lane : Lane.values()) {
                due += queue.dueCount(lane, now);
            }
            regular = due + queue.leaseSize();
        } catch (RuntimeException e) {
            regular = 0L; // Redis 抖动：分母兜底为 0（退化为「已完成数」口径，不报错）
        }
        long slice;
        try {
            slice = partitionFetch.outstandingWork();
        } catch (RuntimeException e) {
            slice = 0L;
        }
        return regular + slice;
    }

    /** 主榜友好名：类型（战力/经验/天梯/战队）+ 区服。 */
    private String mainLabel(String code) {
        var def = catalog.byCode(code).orElse(null);
        if (def == null) {
            return code;
        }
        String kind;
        switch (def.type()) {
            case REDUCED -> kind = code.contains(":382:") ? "同服战力榜" : "战力/经验榜";
            case TEAM -> kind = "战队榜";
            case LADDER -> kind = "天梯榜";
            default -> kind = def.type().name();
        }
        String server = GameServer.nameOfId(def.server());
        return server == null || server.isBlank() ? kind : kind + " · " + server;
    }

    private Map<String, String> sliceNames() {
        long now = System.currentTimeMillis();
        if (now - nameCacheAt < 3000) {
            return nameCache;
        }
        var m = new HashMap<String, String>();
        try {
            for (var k : sliceStore.listKeys()) {
                m.put(k.rankKey(), (k.label() == null || k.label().isBlank()) ? k.rankKey() : k.label());
            }
        } catch (RuntimeException ignore) {
            // best-effort：查不到就用 rankKey 兜底
        }
        nameCache = m;
        nameCacheAt = now;
        return m;
    }
}
