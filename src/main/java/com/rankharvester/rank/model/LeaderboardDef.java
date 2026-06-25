package com.rankharvester.rank.model;

import java.util.List;

/**
 * 排行榜定义 —— 一个「要抓的榜」。
 *
 * <p>{@code code} 全局唯一（同时作为快照 key 与队列 unique_key 基）。
 * {@code type} 决定回包解析方式与落地表；{@code kind} 决定刷新频率与调度 lane；
 * {@code requestParams} 是发给服务器的请求参数，<b>严格照抓包原样</b>（单发一次，offset=0，pageSize 为服务器上限）。
 *
 * @param code          全局唯一榜编码
 * @param type          榜形（解析/落表）
 * @param kind          刷新频率/lane
 * @param gameAccountId 读取该榜所用的游戏账号（同账号多榜复用同一条 TCP 连接）
 * @param server        游戏大区
 * @param partitionId   分区号（无分区填 0）
 * @param requestParams 请求参数（原样发包；Reduced=[rankType,0,10000,0]，Ladder=[23,[4],0,50,0]）
 * @param enabled       是否启用抓取（动态榜默认 false，由后台手动开启当前赛季）
 */
public record LeaderboardDef(
        String code,
        RankType type,
        RankKind kind,
        String gameAccountId,
        String server,
        int partitionId,
        List<Object> requestParams,
        boolean enabled) {

    /** 游戏侧请求方法名（由榜形派生）。 */
    public String fetchFunction() {
        return type.fetchFunction();
    }

    /** 期望回包方法名（由榜形派生）。 */
    public String callbackName() {
        return type.callbackName();
    }

    /** 队列幂等 key：同一榜在同一刷新周期内只入队一次。 */
    public String uniqueKey(long dueBucket) {
        return code + ":" + dueBucket;
    }
}
