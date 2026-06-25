package com.rankharvester.rank.model;

import java.util.List;

/**
 * 一次完整抓取的排行榜快照。仅保留最新一份（用户决策：只留最新）。
 *
 * @param leaderboardCode 榜编码
 * @param kind            榜类型
 * @param snapshotId      本次快照 id（fetchedAtEpochMs 派生，便于覆盖旧快照）
 * @param fetchedAtEpochMs 抓取完成时刻
 * @param rows            行集（按 rank 升序），半截榜不写入（只在完整成功后落地）
 */
public record RankSnapshot(
        String leaderboardCode, RankKind kind, long snapshotId, long fetchedAtEpochMs, List<RankRow> rows) {}
