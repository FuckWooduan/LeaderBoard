package com.rankharvester.rank.fetch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 抓取编排参数。 */
@ConfigurationProperties(prefix = "rankharvester.fetch")
public class FetchProperties {

    /** 编排 tick 间隔（毫秒）。 */
    private long tickMs = 2000;

    /** materializer（任务生产）间隔（毫秒）。 */
    private long materializeMs = 10000;

    /** 单页抓取回包超时（秒）。住宅代理比机房慢，大榜每页传输耗时长，默认 25s（原 15s 过紧致大量超时）。 */
    private int pageTimeoutSeconds = 25;

    /** 单榜分页最多页数（兜底）。 */
    private int maxPages = 200;

    /** 单榜最多行数（兜底，防御异常榜）。 */
    private int maxRows = 50000;

    /**
     * 抓取的连接并发上限（同时在线、用于「抓榜」的 TCP 连接数上界）。
     * 默认 6：抓榜占用 6 个槽，另给玩家在线查询<strong>预留 1 个专属槽</strong>（见 {@code PlayerSearchService}，
     * 单并发、独立信号量），保证查询永远不被抓榜挤占——即「第 7 个槽，专为查询而留」。
     */
    private int accountConcurrency = 6;

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public long getMaterializeMs() {
        return materializeMs;
    }

    public void setMaterializeMs(long materializeMs) {
        this.materializeMs = materializeMs;
    }

    public int getPageTimeoutSeconds() {
        return pageTimeoutSeconds;
    }

    public void setPageTimeoutSeconds(int pageTimeoutSeconds) {
        this.pageTimeoutSeconds = pageTimeoutSeconds;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getAccountConcurrency() {
        return accountConcurrency;
    }

    public void setAccountConcurrency(int accountConcurrency) {
        this.accountConcurrency = accountConcurrency;
    }
}
