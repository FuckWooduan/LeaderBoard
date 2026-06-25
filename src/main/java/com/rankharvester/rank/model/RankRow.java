package com.rankharvester.rank.model;

/**
 * 排行榜单行。
 *
 * @param rank      名次（1-based）
 * @param subjectId 主体 id（玩家 gcid / 战队 id）
 * @param name      显示名（玩家名 / 战队名）
 * @param score     分值（战力 / 经验 / 积分）
 * @param extraJson 其余字段的原始 JSON（服务器名、等级等，按需查询用）
 */
public record RankRow(int rank, String subjectId, String name, long score, String extraJson) {}
