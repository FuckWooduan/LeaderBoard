package com.rankharvester.rank.scheduler;

import com.rankharvester.rank.model.FetchTask;
import com.rankharvester.rank.model.Lane;
import com.rankharvester.rank.queue.RedisTaskQueue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 饥饿调度器 —— 三层策略（与主仓 Engine 设计一致，去掉其已知缺陷）：
 *
 * <ol>
 *   <li><b>Starvation Override</b>：任一 lane 中 overdue 超过 {@link Lane#starvationAfter} 的任务，
 *       跨 lane 最高优先抢占领取，保证慢 lane（如战队周榜）不被快 lane（当前赛季 30 分钟榜）饿死。</li>
 *   <li><b>WDRR</b>：按 lane {@code quantum} 加权赤字轮询；赤字设上界（{@code quantum * capFactor}），
 *       消除主仓里「长期空闲后赤字无界 → 突发领取」的缺陷。</li>
 *   <li><b>Aging</b>：同一 lane 内按 score(dueAt) 升序领取（最久未处理优先），由 Redis ZSET 天然实现。</li>
 * </ol>
 *
 * <p>赤字状态在内存中按 lane 累计；本服务设计为单实例调度（多实例需把赤字外置，超出雏形范围）。
 */
@Component
public class RankStarvationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RankStarvationScheduler.class);

    private final RedisTaskQueue queue;
    private final SchedulerProperties props;
    private final Map<Lane, Integer> deficits = new EnumMap<>(Lane.class);

    public RankStarvationScheduler(RedisTaskQueue queue, SchedulerProperties props) {
        this.queue = queue;
        this.props = props;
    }

    /**
     * 一个调度 tick：先回收过期租约，再按饥饿优先 + WDRR + aging 领取一批任务。
     *
     * @param nowMs 当前时刻
     * @return 本 tick 领取的任务（已写入租约，调用方须在租约到期前 ack/fail）
     */
    public synchronized List<FetchTask> claimBatch(long nowMs) {
        queue.repairExpiredLeases(nowMs, props.getRepairMaxPerTick());

        var claimed = new ArrayList<FetchTask>();
        long leaseUntil = nowMs + props.getLeaseSeconds() * 1000L;
        int budget = props.getMaxBatchPerTick();

        // 1) 饥饿抢占：cutoff = now - starvationAfter，命中的都是「该领却被拖太久」的
        for (Lane lane : Lane.values()) {
            if (budget <= 0) break;
            long cutoff = nowMs - lane.starvationAfter().toMillis();
            var starved = queue.claim(lane, cutoff, budget, leaseUntil);
            if (!starved.isEmpty()) {
                claimed.addAll(starved);
                budget -= starved.size();
                log.debug("饥饿抢占 lane={} 领取 {} 个", lane, starved.size());
            }
        }

        // 2) WDRR：按 quantum 加权赤字轮询（cutoff = now，常规到期任务）
        for (Lane lane : Lane.values()) {
            if (budget <= 0) break;
            int cap = lane.quantum() * Math.max(1, props.getDeficitCapFactor());
            int deficit = Math.min(deficits.getOrDefault(lane, 0) + lane.quantum(), cap);
            int cost = Math.max(1, lane.estimatedCost());

            while (deficit >= cost && budget > 0) {
                int want = Math.min(budget, deficit / cost);
                var batch = queue.claim(lane, nowMs, want, leaseUntil);
                if (batch.isEmpty()) break;
                claimed.addAll(batch);
                budget -= batch.size();
                deficit -= cost * batch.size();
            }
            deficits.put(lane, deficit);
        }

        if (!claimed.isEmpty()) {
            log.debug("本 tick 领取任务 {} 个，租约 {}s", claimed.size(), props.getLeaseSeconds());
        }
        return claimed;
    }
}
