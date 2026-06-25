package com.rankharvester.rank.admin;

import com.rankharvester.rank.model.Lane;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.queue.RedisTaskQueue;
import com.rankharvester.rank.store.LeaderboardCatalog;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 后台手动刷新：把指定榜以**最高优先级**（{@link Lane#MANUAL}）立即入队。
 *
 * <p>独立 taskId（带时间戳）避免被周期任务幂等吸收；{@code dueAt=now} 使其入队即到期，
 * 被调度器饥饿抢占通道最先领取。即便该榜当前是关闭状态，手动刷新也照常触发一次。
 */
@Service
public class ManualRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ManualRefreshService.class);

    private final LeaderboardCatalog catalog;
    private final RedisTaskQueue queue;

    public ManualRefreshService(LeaderboardCatalog catalog, RedisTaskQueue queue) {
        this.catalog = catalog;
        this.queue = queue;
    }

    /**
     * 触发一次最高优先刷新。
     *
     * @return 入队的 taskId
     * @throws IllegalArgumentException 榜不存在
     */
    public String trigger(String code, long nowMs) {
        Optional<LeaderboardDef> def = catalog.byCode(code);
        if (def.isEmpty()) {
            throw new IllegalArgumentException("未知排行榜: " + code);
        }
        String taskId = code + "#manual#" + nowMs;
        queue.enqueue(taskId, code, Lane.MANUAL, nowMs);
        log.info("后台手动刷新（最高优先）已入队: {} -> {}", code, taskId);
        return taskId;
    }
}
