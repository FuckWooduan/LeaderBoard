package com.rankharvester.rank.model;

/**
 * 一个抓取任务（队列中的最小调度单元）。
 *
 * @param id            任务 id（= leaderboardCode:dueBucket）
 * @param leaderboardCode 目标榜
 * @param lane          所属调度 lane
 * @param dueAtEpochMs  到期时刻（≤ now 才可领取）
 * @param readyAtEpochMs 入队时刻（用于 aging 与饥饿判定）
 * @param attempts      已尝试次数
 */
public record FetchTask(
        String id,
        String leaderboardCode,
        Lane lane,
        long dueAtEpochMs,
        long readyAtEpochMs,
        int attempts) {}
