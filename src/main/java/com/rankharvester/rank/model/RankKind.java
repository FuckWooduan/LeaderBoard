package com.rankharvester.rank.model;

import java.time.Duration;

/**
 * 排行榜类型 —— 决定刷新频率、调度 lane 与饥饿阈值。
 *
 * <p>对应用户实际诉求：
 * <ul>
 *   <li>{@link #POWER} 战力榜：每 30 分钟至少刷新一次</li>
 *   <li>{@link #EXP} 经验榜：每 4 小时至少刷新一次</li>
 *   <li>{@link #CURRENT_SEASON} 天梯榜：每天至少刷新一次</li>
 *   <li>{@link #TEAM} 战队榜：每天至少刷新一次</li>
 *   <li>{@link #EXPIRED_SEASON} 过期赛季榜：只完整读取一次写入（当前无此类榜，全部为动态榜）</li>
 * </ul>
 *
 * <p>所有榜均为<b>动态榜</b>，按各自周期持续刷新，使抓取流水线常态有任务、不空闲。
 */
public enum RankKind {
    CURRENT_SEASON(Lane.DAILY, Duration.ofDays(1)),
    POWER(Lane.DYNAMIC_30M, Duration.ofMinutes(30)),
    EXP(Lane.DYNAMIC_4H, Duration.ofHours(4)),
    TEAM(Lane.DAILY, Duration.ofDays(1)),
    EXPIRED_SEASON(Lane.ONCE, null); // 预留：一次性、无刷新周期（当前不使用）

    private final Lane lane;
    private final Duration refreshInterval;

    RankKind(Lane lane, Duration refreshInterval) {
        this.lane = lane;
        this.refreshInterval = refreshInterval;
    }

    public Lane lane() {
        return lane;
    }

    /** 刷新周期；{@link #EXPIRED_SEASON} 返回 null（不重复）。 */
    public Duration refreshInterval() {
        return refreshInterval;
    }

    public boolean recurring() {
        return refreshInterval != null;
    }
}
