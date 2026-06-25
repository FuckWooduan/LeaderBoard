package com.rankharvester.rank.model;

import java.time.Duration;

/**
 * 调度 lane —— 饥饿算法（WDRR + Starvation Override + Aging）的基本单位。
 *
 * <p>每个 lane 有：
 * <ul>
 *   <li>{@code quantum}：WDRR 加权赤字轮询的权重，越大每轮能领越多任务（体现优先级）。</li>
 *   <li>{@code starvationAfter}：任务 ready 超过此时长仍未被领取，进入全局饥饿优先通道，
 *       跨 lane 抢占执行 —— 保证慢 lane 不会被快 lane 永久饿死。</li>
 *   <li>{@code estimatedCost}：该 lane 单任务的预估成本（领取时从赤字扣减）。</li>
 * </ul>
 *
 * <p>当前赛季榜（30 分钟刷新、要求持续轮询）给最高 quantum 与最短饥饿阈值。
 *
 * <p>{@link #MANUAL} 是后台手动刷新专用通道：声明在最前（饥饿抢占按 enum 顺序遍历，最先领取），
 * {@code starvationAfter=0} 使其入队即视为「已超时」被最高优先抢占执行 —— 即「后台刷新指令级别最高」。
 */
public enum Lane {
    MANUAL(100, Duration.ZERO, 1),
    DYNAMIC_30M(40, Duration.ofMinutes(8), 1),   // 战力：每 30 分钟至少一次
    DYNAMIC_4H(20, Duration.ofMinutes(45), 2),   // 经验：每 4 小时至少一次
    DAILY(6, Duration.ofHours(6), 4),            // 天梯 / 战队：每天至少一次
    ONCE(10, Duration.ofHours(2), 3),
    RETRY(4, Duration.ofMinutes(30), 1);

    private final int quantum;
    private final Duration starvationAfter;
    private final int estimatedCost;

    Lane(int quantum, Duration starvationAfter, int estimatedCost) {
        this.quantum = quantum;
        this.starvationAfter = starvationAfter;
        this.estimatedCost = estimatedCost;
    }

    public int quantum() {
        return quantum;
    }

    public Duration starvationAfter() {
        return starvationAfter;
    }

    public int estimatedCost() {
        return estimatedCost;
    }
}
