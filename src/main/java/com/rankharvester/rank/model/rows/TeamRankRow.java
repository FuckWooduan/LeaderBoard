package com.rankharvester.rank.model.rows;

/**
 * 战队榜（Team）单行。落地表 {@code team_rank}。
 *
 * <p>{@code rank} 由 offset+下标 计算（服务器不返回名次）；主分值为 {@code teamExp}。
 */
public record TeamRankRow(
        int rank,
        Long teamId,
        String teamName,
        String leaderName,
        Integer teamLv,
        Long teamExp,
        Integer mNumber,
        Integer mNumberLimit,
        String declaration,
        Integer rpgTeamCapacity,
        Integer rpgTeamLv,
        Integer rpgTeamNumber,
        Long rpgTeamTotalBonus,
        String rpgTeamDeclaration) {}
