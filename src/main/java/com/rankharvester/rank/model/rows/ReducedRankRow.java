package com.rankharvester.rank.model.rows;

/**
 * 战力/经验榜（Reduced，同服/跨服）单行 —— 字段严格对齐真实回包
 * {@code callbackGetReducedRankByPage} 的 {@code bean.RankBean}。
 *
 * <p>嵌套 {@code infoBean}({@code bean.ClientRpgDataRankBean})、{@code scoreBean}({@code bean.RpgDataScore})
 * 以 {@code info_*}/{@code score_*} 前缀拍平；不含 {@code __AMF_CLASS__} 等原始字段。
 * {@code rank} 由 offset+下标 计算，{@code rankType} 取自回包 parameters[1]（331 同服经验 /
 * 332 跨服经验 / 333 跨服战力 / 382 同服战力）。回包中 {@code number}/{@code allNumber} 为数字字符串，已解析为 long。
 */
public record ReducedRankRow(
        int rank,
        int rankType,
        // RankBean
        Long lastRank,
        Long dateline,
        Integer updateDisplayCount,
        Long rankId,
        Boolean display,
        Long uniqueIdBean,
        Long lastChangeDisplayFlag,
        // infoBean
        String infoTeamName,
        Integer infoBlueVipType,
        Long infoLoginId,
        Integer infoDouwaVip,
        Boolean infoHomelandOpen,
        Long infoCharId,
        Integer infoLv,
        String infoCharName,
        Integer infoOperatorId,
        Integer infoBlueVipLevel,
        // scoreBean
        Long scoreAllNumber,
        Long scoreNumber,
        Long scoreDateline,
        Long scoreGcId,
        Integer scoreRebirth) {}
