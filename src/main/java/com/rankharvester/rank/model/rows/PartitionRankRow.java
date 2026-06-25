package com.rankharvester.rank.model.rows;

/**
 * 分区静态榜（RankKey）单行。最宽的榜形，统一 3 种 infoBean（奖励/种族/王者乱斗）与
 * 2 种 scoreBean（自然序/全局成就）。落地表 {@code partition_rank_<rankKey>}（一表一 rankKey）。
 *
 * <p>{@code scoreNumber}/{@code scoreExtraNumber} 用 double，因可能为小数（如 "306900.0"）。
 * 变体专属字段在不适用时为 null。不含原始 raw bean。
 */
public record PartitionRankRow(
        int rank,
        int rowIndex,
        String rankBeanClass,
        Long lastRank,
        Long dateline,
        Integer updateDisplayCount,
        Long rankId,
        Boolean display,
        String uniqueIdBean,
        Long lastChangeDisplayFlag,
        // infoBean（联合）
        String infoClass,
        String infoTeamName,
        Long infoLoginId,
        Long infoCharId,
        Long infoLv,
        String infoCharName,
        Integer infoOperatorId,
        Long infoBonus,
        Long infoTeamId,
        Long infoLastRank,
        Integer infoBlueVipType,
        Long infoAllNumber,
        Integer infoMedalIndex,
        Integer infoDouwaVip,
        Integer infoUpdateDisplayCount,
        Boolean infoDisplay,
        Long infoLastChangeDisplayFlag,
        Long infoExtraValue,
        Long infoMedalLine,
        Long infoCurRank,
        Long infoNumber,
        Integer infoVipLv,
        Integer infoBlueVipLevel,
        // scoreBean（联合）
        String scoreClass,
        Double scoreNumber,
        Double scoreExtraNumber,
        Long scoreDateline,
        String scoreGcId,
        Integer scoreRebirth) {}
