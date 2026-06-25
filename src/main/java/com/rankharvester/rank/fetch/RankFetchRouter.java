package com.rankharvester.rank.fetch;

import com.rankharvester.apc.ApcObject;
import com.rankharvester.net.GameConnection;
import com.rankharvester.rank.fetch.parse.LadderRankParser;
import com.rankharvester.rank.fetch.parse.ReducedRankParser;
import com.rankharvester.rank.fetch.parse.TeamRankParser;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.model.rows.ReducedRankRow;
import com.rankharvester.rank.model.rows.TeamRankRow;
import com.rankharvester.rank.store.LadderRankStore;
import com.rankharvester.rank.store.RankResumeStore;
import com.rankharvester.rank.store.ReducedRankStore;
import com.rankharvester.rank.store.TeamRankStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按榜形分派抓取：<b>统一分页 + 断点续传</b>。逐页改写 offset（原样照抓包）→ 选对应解析器 →
 * 逐页 append 到 {@code <table>_staging} 影子表 + 记录检查点 → 抓满后 promote 原子换入正式表。
 *
 * <p>分页推进按<b>实际返回行数</b>（不假设固定单页条数：战力榜每页 1000、战队榜服务端每页 8）。终止条件：
 * <ul>
 *   <li><b>战队榜</b>（{@link RankType#returnsTotal()}）：回包带总数 → {@code offset ≥ total} 即停；</li>
 *   <li><b>其余榜</b>（战力/经验/天梯/分区）：不返回总数 → 「短页」（本页行数 &lt; pageSize）或空页即停；</li>
 *   <li>兜底：累计行数 ≥ maxRows、或检测到重复页（服务端忽略 offset）即停。</li>
 * </ul>
 *
 * <p><b>断点续传</b>：每页写 staging 后即更新检查点（snapshot/next_offset/total）。中途任一页失败抛出 →
 * 不 promote（不写半截正式表）→ 检查点与 staging 保留 → 调用方退避重试时从 next_offset 续抓，
 * 不必从头重来（大战队榜 ~数百页时尤为关键）。抓满才 promote + 清检查点。
 */
@Component
public class RankFetchRouter {

    private static final Logger log = LoggerFactory.getLogger(RankFetchRouter.class);

    /** 全局唯一递增的 snapshot 序号，保证并发抓取互不串行（避免 staging 重复）。 */
    private static final AtomicLong SNAP_SEQ = new AtomicLong(System.currentTimeMillis());

    private final ReducedRankParser reducedParser;
    private final LadderRankParser ladderParser;
    private final TeamRankParser teamParser;
    private final ReducedRankStore reducedStore;
    private final LadderRankStore ladderStore;
    private final TeamRankStore teamStore;
    private final RankResumeStore resume;

    /** 页间最小间隔（毫秒），平滑突发、规避风控。 */
    private final long pageIntervalMs;
    /** 单榜单次抓取最多页数（兜底，防服务端异常/死循环；未抓完则抛出续抓）。 */
    private final int maxPages;
    /** 单榜最多行数（兜底，防极端榜撑爆）。 */
    private final int maxRows;

    public RankFetchRouter(
            ReducedRankParser reducedParser,
            LadderRankParser ladderParser,
            TeamRankParser teamParser,
            ReducedRankStore reducedStore,
            LadderRankStore ladderStore,
            TeamRankStore teamStore,
            RankResumeStore resume,
            @Value("${rankharvester.fetch.page-interval-ms:100}") long pageIntervalMs,
            @Value("${rankharvester.fetch.max-pages:1000}") int maxPages,
            @Value("${rankharvester.fetch.max-rows:50000}") int maxRows) {
        this.reducedParser = reducedParser;
        this.ladderParser = ladderParser;
        this.teamParser = teamParser;
        this.reducedStore = reducedStore;
        this.ladderStore = ladderStore;
        this.teamStore = teamStore;
        this.resume = resume;
        this.pageIntervalMs = pageIntervalMs;
        this.maxPages = Math.max(1, maxPages);
        this.maxRows = Math.max(1, maxRows);
    }

    /**
     * 抓取并落地某榜的最新快照（分页 + 断点续传），返回写入行数。
     *
     * @param pageTimeout 单页 sendAndAwait 超时
     * @throws RuntimeException 连接/超时/服务器异常 / 未抓完（由调用方退避重试 → 续抓）
     */
    public int fetchAndStore(GameConnection conn, LeaderboardDef def, Duration pageTimeout) {
        return switch (def.type()) {
            case REDUCED, PARTITION -> paginate(conn, def, pageTimeout,
                    (params, rankBase) -> {
                        var p = reducedParser.parsePage(params, rankBase);
                        return new Page<>(p.rows(), p.total());
                    },
                    // 去重按 uniqueIdBean（rank 每页不同不能进指纹），防服务端忽略页码反复返回同一页。
                    ReducedRankRow::uniqueIdBean,
                    // reduced：增量批量 append 直写正式表，抓满后按快照清旧行（读时取最新快照去重；无 staging/promote）。
                    new Sink<ReducedRankRow>() {
                        @Override public void writePage(String code, int sid, long snap, long fa, List<ReducedRankRow> rows) {
                            reducedStore.appendPage(code, sid, snap, fa, rows);
                        }
                        @Override public void freshStart(String code, long snap) { /* 增量直写，无残留可清 */ }
                        @Override public void complete(String code, long snap) { reducedStore.prune(code, snap); }
                    });
            case LADDER -> paginate(conn, def, pageTimeout,
                    (params, rankBase) -> new Page<>(ladderParser.parse(params), null),
                    r -> r, stagingSink("ladder_rank", ladderStore::appendStaging));
            case TEAM -> paginate(conn, def, pageTimeout,
                    (params, rankBase) -> {
                        var p = teamParser.parse(params, rankBase);
                        return new Page<>(p.rows(), p.total());
                    },
                    // 去重按 teamId（rank 每页不同，不能进指纹），防服务端忽略 offset 时把同一页刷成数百页。
                    TeamRankRow::teamId, stagingSink("team_rank", teamStore::appendStaging));
        };
    }

    /** 单页解析结果：结构化行 + 可选总数（仅战队榜非 null）。 */
    private record Page<R>(List<R> rows, Integer total) {}

    /** 单页解析器：入参为回包 parameters + 本页发起 offset。 */
    @FunctionalInterface
    private interface PageParse<R> {
        Page<R> parse(List<Object> parameters, int offset);
    }

    /** 把一页写入落地层。 */
    @FunctionalInterface
    private interface AppendFn<R> {
        void append(String code, int serverId, long snapshotId, long fetchedAt, List<R> rows);
    }

    /**
     * 落地策略：逐页写 + 开抓前清残留 + 抓满收尾。reduced 走增量 UPSERT+prune；team/ladder 走 staging+promote。
     */
    private interface Sink<R> {
        void writePage(String code, int serverId, long snapshotId, long fetchedAt, List<R> rows);
        void freshStart(String code, long snapshotId);
        void complete(String code, long snapshotId);
    }

    /** team/ladder 的 staging+promote 落地策略。 */
    private <R> Sink<R> stagingSink(String realTable, AppendFn<R> append) {
        return new Sink<R>() {
            @Override public void writePage(String code, int sid, long snap, long fa, List<R> rows) {
                append.append(code, sid, snap, fa, rows);
            }
            @Override public void freshStart(String code, long snap) { resume.clearStaging(realTable, code, snap); }
            @Override public void complete(String code, long snap) { resume.promote(realTable, code, snap); }
        };
    }

    /**
     * 统一分页循环（含断点续传）。每页写 staging + 更新检查点；抓满后 promote 换入正式表。
     *
     * @param keyFn 行的去重键（战队榜按 teamId；其余按行本身），检测服务端忽略 offset 反复返回同一页。
     * @param sink  落地策略：逐页写 / 开抓前清残留 / 抓满收尾（reduced 增量 UPSERT，team/ladder staging+promote）。
     */
    private <R> int paginate(
            GameConnection conn,
            LeaderboardDef def,
            Duration pageTimeout,
            PageParse<R> parser,
            Function<R, Object> keyFn,
            Sink<R> sink) {
        String code = def.code();
        int serverId = parseServerId(def.server());
        long now = System.currentTimeMillis();
        RankType type = def.type();
        int offsetIdx = type.offsetParamIndex();
        int psIdx = type.pageSizeParamIndex();
        // 仅用于「无总数榜」的短页判定：请求声明的每页条数（战队榜无此参数，靠 total 判完成）。
        int pageSize = psIdx >= 0 && psIdx < def.requestParams().size()
                ? Math.max(1, toInt(def.requestParams().get(psIdx), type.fixedPageSize()))
                : type.fixedPageSize();

        boolean pageIndexed = type.cursorMode() == RankType.CursorMode.PAGE_INDEX;

        long snapshotId;
        int rowsFetched; // 已抓累计行数，既是断点续传游标，也是下一页的 rankBase
        Integer total;
        var prog = resume.loadProgress(code);
        if (prog.isPresent()) {
            snapshotId = prog.get().snapshotId();
            rowsFetched = prog.get().nextOffset();
            total = prog.get().total();
            // 页码榜（PAGE_INDEX）若上轮已落在短页（rowsFetched 非整页倍数）→ 其实已抓完只是没 promote：
            // 直接收尾，避免按 rowsFetched/pageSize 又重抓最后一页造成重复行。
            if (pageIndexed && rowsFetched % pageSize != 0) {
                sink.complete(code, snapshotId);
                resume.clearProgress(code);
                log.info("[{}] 断点续传：上轮已抓满 {} 行，直接收尾", code, rowsFetched);
                return rowsFetched;
            }
            log.info("[{}] 断点续传：从 {} 行处续抓（snapshot={}, total={}）", code, rowsFetched, snapshotId, total);
        } else {
            snapshotId = SNAP_SEQ.incrementAndGet(); // 全局唯一，避免同毫秒并发抓取拿到相同 snapshot 致重复
            rowsFetched = 0;
            total = null;
            sink.freshStart(code, snapshotId); // 清掉本快照可能残留（staging 残留 / reduced 无操作）
        }

        long lastFingerprint = Long.MIN_VALUE;
        boolean completed = false;

        for (int page = 0; page < maxPages; page++) {
            if (page > 0 && pageIntervalMs > 0) {
                try {
                    Thread.sleep(pageIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("分页抓取被中断: " + code, e);
                }
            }

            // 请求游标：页码榜=rowsFetched/pageSize（前序皆满页），行偏移榜=rowsFetched。
            int cursor = pageIndexed ? rowsFetched / pageSize : rowsFetched;
            Object[] params = def.requestParams().toArray();
            if (offsetIdx >= 0 && offsetIdx < params.length) {
                params[offsetIdx] = cursor;
            }
            // 任一页失败在此抛出：检查点已持久化到上一成功页 → 重抓时续传，不写半截。
            // 任一页失败在此抛出：检查点已持久化到上一成功页 → 重抓时续传，不写半截。
            ApcObject cb = conn.call(def.fetchFunction(), def.callbackName(), pageTimeout, params);
            Page<R> p = parser.parse(cb.getParameters(), rowsFetched); // rankBase = 已抓累计行数
            if (p.total() != null) {
                total = p.total();
            }
            int n = p.rows().size();
            if (n == 0) {
                completed = true; // 空页：到底
                break;
            }

            long fp = fingerprint(p.rows(), keyFn);
            if (page > 0 && fp == lastFingerprint) {
                log.warn("[{}] 检测到重复页（服务端疑似忽略游标），在 {} 行处终止", code, rowsFetched);
                completed = true;
                break;
            }
            lastFingerprint = fp;

            sink.writePage(code, serverId, snapshotId, now, p.rows());
            rowsFetched += n;
            resume.saveProgress(code, snapshotId, rowsFetched, total, now);

            // 多证据终止：累计达上限 / 覆盖 total(最大条数) / 短页（本页 < pageSize）。
            if (rowsFetched >= maxRows) {
                log.warn("[{}] 达到 maxRows={} 上限，终止分页（已取 {} 行）", code, maxRows, rowsFetched);
                completed = true;
                break;
            }
            if (total != null && rowsFetched >= total) {
                completed = true; // 已覆盖服务端最大条数
                break;
            }
            if (n < pageSize) {
                completed = true; // 短页：到底
                break;
            }
        }

        if (!completed) {
            // 单次抓取 maxPages 页仍未抓完：抛出让其退避续抓（检查点保留，不 promote 半截）。
            throw new IllegalStateException(
                    "[" + code + "] 分页未在 maxPages=" + maxPages + " 内抓完（已 " + rowsFetched + " 行），将续抓");
        }

        sink.complete(code, snapshotId); // reduced=清陈旧行；team/ladder=promote 原子换入
        resume.clearProgress(code);
        if (type.returnsTotal()) {
            log.info("[{}] 分页结束：实抓 {} 行，服务端 total/最大={}", code, rowsFetched, total);
        }
        return rowsFetched; // 累计抓取行数
    }

    /** 用去重键集合的 hash 做页指纹，检测服务端忽略 offset 反复返回同一页的死循环。 */
    private static <R> long fingerprint(List<R> rows, Function<R, Object> keyFn) {
        long h = 1125899906842597L;
        for (R r : rows) {
            Object k = r == null ? null : keyFn.apply(r);
            h = 31 * h + (k == null ? 0 : k.hashCode());
        }
        return h;
    }

    private static int toInt(Object v, int dft) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return dft;
            }
        }
        return dft;
    }

    private static int parseServerId(String server) {
        try {
            return Integer.parseInt(server.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
