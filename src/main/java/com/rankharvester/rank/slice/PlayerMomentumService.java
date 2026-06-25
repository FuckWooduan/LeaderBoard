package com.rankharvester.rank.slice;

import com.rankharvester.rank.store.PlayerMomentumStore;
import com.rankharvester.rank.store.ReducedRankStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 每小时把当前经验榜的 (经验, 等级) 写入 {@link PlayerMomentumStore}，积累出「动量」（变化量）。
 * 第一次只有基线、没差值；之后每次写入即可算出 Δ经验/Δ等级，供隐藏预测判活跃度。
 */
@Service
public class PlayerMomentumService {

    private static final Logger log = LoggerFactory.getLogger(PlayerMomentumService.class);

    private final ReducedRankStore reduced;
    private final PlayerMomentumStore momentum;

    public PlayerMomentumService(ReducedRankStore reduced, PlayerMomentumStore momentum) {
        this.reduced = reduced;
        this.momentum = momentum;
    }

    /** 每小时记一次（首跑延迟 5 分钟，等经验榜先抓上）。 */
    @Scheduled(fixedDelay = 60 * 60_000L, initialDelay = 5 * 60_000L)
    void tick() {
        try {
            var rows = reduced.latestExpLvByGcId();
            if (rows.isEmpty()) {
                return;
            }
            momentum.recordSnapshot(rows, System.currentTimeMillis());
            log.info("[Momentum] 记录经验/等级动量快照 {} 人", rows.size());
        } catch (RuntimeException e) {
            log.warn("[Momentum] 记录动量失败（忽略）: {}", e.getMessage());
        }
    }
}
