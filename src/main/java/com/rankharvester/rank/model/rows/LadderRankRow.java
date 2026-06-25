package com.rankharvester.rank.model.rows;

/**
 * 天梯联赛榜（Ladder）单行 —— 字段严格对齐真实回包 {@code callbackGetRankNew} 的
 * {@code bean.ClientRankKingBean}。不含 {@code __AMF_CLASS__} 等原始字段。
 *
 * <p>{@code rank} 取 {@code curRank}（>0）否则由 offset+下标 计算；主分值为 {@code number}（积分）。
 */
public record LadderRankRow(
        int rank,
        String teamName,
        Integer lastRank,
        Integer medalIndex,
        Long loginId,
        Long charId,
        Integer lv,
        String userName,
        String charName,
        Long extraValue,
        Integer medalLine,
        Integer curRank,
        Long number,
        Long teamId,
        Integer operatorId) {}
