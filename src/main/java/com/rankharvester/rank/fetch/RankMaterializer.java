package com.rankharvester.rank.fetch;

import com.rankharvester.rank.admin.BoardStateService;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.queue.RedisTaskQueue;
import com.rankharvester.rank.store.LeaderboardCatalog;
import com.rankharvester.rank.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务生产者（materializer）：按各榜刷新周期把「到期任务」幂等入队。
 *
 * <ul>
 *   <li>周期性榜：bucket = now / refreshInterval，taskId = code#bucket，dueAt = bucket 起点（即已到期）。
 *       新窗口自然产生新 bucket → 新任务；同窗口重复入队被队列幂等吸收。</li>
 *   <li>过期赛季榜（一次性）：仅当尚无快照时入队一次，抓完即不再产生。</li>
 * </ul>
 */
@Component
public class RankMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RankMaterializer.class);

    private final LeaderboardCatalog catalog;
    private final RedisTaskQueue queue;
    private final SnapshotStore store;
    private final BoardStateService boardState;

    public RankMaterializer(
            LeaderboardCatalog catalog, RedisTaskQueue queue, SnapshotStore store, BoardStateService boardState) {
        this.catalog = catalog;
        this.queue = queue;
        this.store = store;
        this.boardState = boardState;
    }

    @Scheduled(fixedDelayString = "${rankharvester.fetch.materialize-ms:10000}")
    public void materialize() {
        long now = System.currentTimeMillis();
        int produced = 0;
        for (LeaderboardDef def : catalog.all()) {
            if (!boardState.isEnabled(def)) continue;
            if (def.kind().recurring()) {
                long interval = def.kind().refreshInterval().toMillis();
                long bucket = now / interval;
                long dueAt = bucket * interval;
                queue.enqueue(def.code() + "#" + bucket, def.code(), def.kind().lane(), dueAt);
                produced++;
            } else {
                // 一次性：无快照才入队
                if (store.latestMeta(def.code()).isEmpty()) {
                    queue.enqueue(def.code() + "#0", def.code(), def.kind().lane(), now);
                    produced++;
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("materialize 巡检 {} 个榜（含已存在幂等跳过）", produced);
        }
    }
}
