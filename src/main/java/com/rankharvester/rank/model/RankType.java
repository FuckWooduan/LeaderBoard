package com.rankharvester.rank.model;

/**
 * 排行榜「榜形」—— 决定回包解析方式与落地数据表（不同于决定刷新频率的 {@link RankKind}）。
 *
 * <p>四种榜形对应四套解析器与表结构（分区静态榜按 rankKey 一表一个）：
 * <ul>
 *   <li>{@link #REDUCED} 战力/经验榜（同服/跨服，按 rankType 331/332/333/382 区分） → 表 {@code reduced_rank}</li>
 *   <li>{@link #TEAM} 战队榜 → 表 {@code team_rank}</li>
 *   <li>{@link #LADDER} 天梯联赛榜 → 表 {@code ladder_rank}</li>
 *   <li>{@link #PARTITION} 分区静态榜（按 rankKey） → 表 {@code partition_rank_<rankKey>}</li>
 * </ul>
 */
public enum RankType {
    // offsetParamIndex / pageSizeParamIndex 指向 requestParams 中「翻页游标 / 每页条数」的位置。
    // cursorMode 决定游标语义：
    //   · PAGE_INDEX —— 游标是「页码」，每次 +1（战力/经验：getReducedRankByPage(rankType, pageIndex, 1000, 0)）；
    //   · ROW_OFFSET —— 游标是「行偏移」，每次 +本页行数（战队：getTeamList(rowOffset)，服务端每页 8）。
    // returnsTotal=true 表示回包带「最大条数/总数」：战力经验在 parameters[5]（cap，如 1000）、战队在 parameters[1]。
    // 终止：空页 / 短页(本页<pageSize) / 累计≥total，任一即停（多证据兜底）。
    REDUCED("getReducedRankByPage", "callbackGetReducedRankByPage", 1, 2, 1000, true, CursorMode.PAGE_INDEX),
    TEAM("getTeamList", "onGetTeamList", 0, -1, 8, true, CursorMode.ROW_OFFSET),
    LADDER("getRankNew", "callbackGetRankNew", 2, 3, 50, false, CursorMode.PAGE_INDEX),
    PARTITION("getReducedRankByPage", "callbackGetReducedRankByPage", 1, 2, 1000, true, CursorMode.PAGE_INDEX);

    /** 翻页游标语义。 */
    public enum CursorMode {
        /** 游标是页码（0,1,2…），每页 +1。 */
        PAGE_INDEX,
        /** 游标是行偏移，每页 +本页实际行数。 */
        ROW_OFFSET
    }

    private final String fetchFunction;
    private final String callbackName;
    private final int offsetParamIndex;
    private final int pageSizeParamIndex;
    private final int fixedPageSize;
    private final boolean returnsTotal;
    private final CursorMode cursorMode;

    RankType(
            String fetchFunction,
            String callbackName,
            int offsetParamIndex,
            int pageSizeParamIndex,
            int fixedPageSize,
            boolean returnsTotal,
            CursorMode cursorMode) {
        this.fetchFunction = fetchFunction;
        this.callbackName = callbackName;
        this.offsetParamIndex = offsetParamIndex;
        this.pageSizeParamIndex = pageSizeParamIndex;
        this.fixedPageSize = fixedPageSize;
        this.returnsTotal = returnsTotal;
        this.cursorMode = cursorMode;
    }

    /** 翻页游标语义（页码 / 行偏移）。 */
    public CursorMode cursorMode() {
        return cursorMode;
    }

    public String fetchFunction() {
        return fetchFunction;
    }

    public String callbackName() {
        return callbackName;
    }

    /** requestParams 中翻页游标（offset）的下标。 */
    public int offsetParamIndex() {
        return offsetParamIndex;
    }

    /** requestParams 中每页条数的下标；<0 表示请求不带 pageSize（用 {@link #fixedPageSize()}）。 */
    public int pageSizeParamIndex() {
        return pageSizeParamIndex;
    }

    /** 请求不带 pageSize 参数时的固定每页条数（短页判定用）。 */
    public int fixedPageSize() {
        return fixedPageSize;
    }

    /** 回包是否带总数（仅战队榜）。 */
    public boolean returnsTotal() {
        return returnsTotal;
    }
}
