package com.rankharvester.web;

import com.rankharvester.rank.slice.PartitionRankFetchService;
import com.rankharvester.rank.slice.PartitionRankStore;
import com.rankharvester.rank.slice.RankSliceFetchService;
import com.rankharvester.rank.slice.RankSliceStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分区排行榜「分区获取」接口。
 * <ul>
 *   <li>公开读：{@code /api/public/slice/keys|grid|progress}（前端进度页）。</li>
 *   <li>管理：{@code /api/boards/slice/label|dynamic|refetch}（受 AdminAuthFilter 保护）。</li>
 * </ul>
 */
@RestController
public class RankSliceController {

    private final RankSliceStore store;
    private final RankSliceFetchService fetch;
    private final PartitionRankStore rankStore;
    private final PartitionRankFetchService rankFetch;
    private final com.rankharvester.rank.slice.SliceSubscriptionService subs;
    private final com.rankharvester.rank.store.ReducedRankStore reducedStore;
    private final AiPredictor ai;
    private final com.rankharvester.rank.slice.HiddenPredictionService prediction;

    private final CaptchaService captcha;

    public RankSliceController(RankSliceStore store, RankSliceFetchService fetch,
            PartitionRankStore rankStore, PartitionRankFetchService rankFetch,
            com.rankharvester.rank.slice.SliceSubscriptionService subs,
            com.rankharvester.rank.store.ReducedRankStore reducedStore, AiPredictor ai,
            com.rankharvester.rank.slice.HiddenPredictionService prediction, CaptchaService captcha) {
        this.store = store;
        this.fetch = fetch;
        this.rankStore = rankStore;
        this.rankFetch = rankFetch;
        this.subs = subs;
        this.reducedStore = reducedStore;
        this.ai = ai;
        this.prediction = prediction;
        this.captcha = captcha;
    }

    /** AI 身份预测结果缓存：key=rankKey#partition → {ts, predictions}，避免重复花钱调模型。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> aiCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long AI_CACHE_TTL = 30 * 60_000L;

    private boolean gtOk(Map<String, Object> body) {
        return captcha.verify(str(body, "cfToken"), str(body, "gtPayload"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** 订阅：某 (rankKey, 分区) 里某玩家，排名变动发邮件到 email。 */
    @PostMapping("/api/public/slice/subscribe")
    public Map<String, Object> subscribe(@RequestBody Map<String, Object> body) {
        if (!gtOk(body)) {
            return Map.of("ok", false, "message", "人机验证未通过，请重试");
        }
        String email = String.valueOf(body.getOrDefault("email", "")).trim();
        String rankKey = String.valueOf(body.getOrDefault("rankKey", "")).trim();
        String charName = String.valueOf(body.getOrDefault("charName", "")).trim();
        Integer partition = body.get("partition") instanceof Number n ? n.intValue() : null;
        if (!EMAIL.matcher(email).matches()) {
            return Map.of("ok", false, "message", "邮箱格式不正确");
        }
        if (rankKey.isBlank() || charName.isBlank() || partition == null) {
            return Map.of("ok", false, "message", "参数不完整");
        }
        subs.subscribe(email, rankKey, partition, charName);
        return Map.of("ok", true, "message", "订阅成功，" + charName + " 排名变动时会邮件通知你");
    }

    /** 即刻抓取：对某 rankKey 立即抓一遍所有分区，完成后给 email 发邮件通知。受 Turnstile 限流。 */
    @PostMapping("/api/public/slice/fetch-now")
    public Map<String, Object> fetchNow(@RequestBody Map<String, Object> body) {
        if (!gtOk(body)) {
            return Map.of("ok", false, "message", "人机验证未通过，请重试");
        }
        String email = String.valueOf(body.getOrDefault("email", "")).trim();
        String rankKey = String.valueOf(body.getOrDefault("rankKey", "")).trim();
        if (!EMAIL.matcher(email).matches()) {
            return Map.of("ok", false, "message", "邮箱格式不正确");
        }
        if (rankKey.isBlank()) {
            return Map.of("ok", false, "message", "缺少 rankKey");
        }
        int n = rankFetch.fetchRankKeyNow(rankKey, email);
        if (n <= 0) {
            return Map.of("ok", false, "message", "该榜暂无可抓取的分区来源");
        }
        return Map.of("ok", true, "partitions", n,
                "message", "已开始抓取 " + n + " 个分区，完成后会邮件通知 " + email);
    }

    /** 即刻抓取<b>单个分区</b>（快，无需邮箱）。入参 {@code {rankKey, partition}}（partition 0 起始）。受 Turnstile 限流。 */
    @PostMapping("/api/public/slice/fetch-partition")
    public Map<String, Object> fetchPartition(@RequestBody Map<String, Object> body) {
        if (!gtOk(body)) {
            return Map.of("ok", false, "message", "人机验证未通过，请重试");
        }
        String rankKey = String.valueOf(body.getOrDefault("rankKey", "")).trim();
        if (rankKey.isBlank()) {
            return Map.of("ok", false, "message", "缺少 rankKey");
        }
        Object pv = body.get("partition");
        if (!(pv instanceof Number)) {
            return Map.of("ok", false, "message", "缺少 partition（0 起始）");
        }
        int partition = ((Number) pv).intValue();
        boolean ok = rankFetch.fetchPartitionNow(rankKey, partition);
        return ok
                ? Map.of("ok", true, "message", "已开始抓取分区 " + (partition + 1) + "，约 10 秒后刷新可见")
                : Map.of("ok", false, "message", "该分区暂无可用来源号，抓不了");
    }

    /** 退订。 */
    @PostMapping("/api/public/slice/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody Map<String, Object> body) {
        String email = String.valueOf(body.getOrDefault("email", "")).trim();
        String rankKey = String.valueOf(body.getOrDefault("rankKey", "")).trim();
        String charName = String.valueOf(body.getOrDefault("charName", "")).trim();
        Integer partition = body.get("partition") instanceof Number n ? n.intValue() : null;
        if (partition != null) {
            subs.unsubscribe(email, rankKey, partition, charName);
        }
        return Map.of("ok", true);
    }

    private static final int PAGE = 100;

    /** 分区排行榜数据（渲染同跨服战力）：单分区，或 all=全部分区汇总(末尾加「分区」列)。 */
    @GetMapping("/api/public/slice/board")
    public Map<String, Object> board(
            @RequestParam String rankKey,
            @RequestParam(required = false) Integer partition,
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) String view,
            @RequestParam(defaultValue = "1") int page) {
        List<Map<String, Object>> rows;
        long total;
        boolean dragon = "dragon".equalsIgnoreCase(view);
        boolean aggregate = dragon || all || partition == null;
        if (dragon) {
            // 龙虎榜：各分区第1名→第2名…（按分区内名次升序、同名次按分数降序）；保留分区内名次列，
            // 但奖牌/前三高亮按「整体顺序」给（全局连续位次 medal_rank），符合「龙虎榜第1名第2名」直觉。
            rows = rankStore.pageDragon(rankKey, page, PAGE);
            total = rankStore.countOf(rankKey, null);
            int base = (page - 1) * PAGE;
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).put("medal_rank", base + i + 1);
            }
        } else if (aggregate) {
            rows = rankStore.pageAll(rankKey, page, PAGE);
            total = rankStore.countOf(rankKey, null);
            // 汇总=跨分区按分数全局排序：名次用「全局连续位次」覆盖各分区内名次，
            // 否则会出现 4,7,9,12,12,16… 乱跳/重复（那是各自分区里的名次）。
            int base = (page - 1) * PAGE;
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).put("rank", base + i + 1);
                rows.get(i).put("medal_rank", base + i + 1);
            }
        } else {
            rows = rankStore.page(rankKey, partition, page, PAGE);
            total = rankStore.countOf(rankKey, partition);
            // 单分区：奖牌按分区内真实名次
            for (var r : rows) {
                Object rk = r.get("rank");
                if (rk instanceof Number n) {
                    r.put("medal_rank", n.intValue());
                }
            }
        }
        // 分区号 0-based → 显示 1-based
        for (var r : rows) {
            Object pi = r.get("partition_idx");
            if (pi instanceof Number n) {
                r.put("partition", n.intValue() + 1);
            }
        }
        // 先做隐藏玩家预测（会给预测行补 区服/等级/战队/gc_id），再按 gc_id 补战力/VIP/境界——顺序不能反。
        prediction.applyPredictions(rankKey, partition, rows, aggregate);
        enrichWithPower(rows); // 用 gc_id 去同服战力榜(382)补：战力/VIP等级/境界（预测行的 gc_id 也已补上）
        var fields = new ArrayList<Map<String, Object>>();
        fields.add(field("rank", "名次", "TEXT"));
        fields.add(field("char_name", "角色名", "TEXT"));
        fields.add(field("login_id", "区服", "DISTRICT"));
        fields.add(field("lv", "等级", "NUMBER"));
        fields.add(field("team_name", "战队", "TEXT"));
        fields.add(field("score_number", "分数", "NUMBER"));
        fields.add(field("power_number", "战力", "NUMBER"));
        fields.add(field("power_vip", "VIP等级", "VIP"));
        fields.add(field("power_realm", "境界", "REALM"));
        fields.add(field("dateline", "更新时间", "TIME")); // 游戏侧该行更新时间(真实)；旧 last_change 恒为 0 已弃用
        if (aggregate) {
            fields.add(field("partition", "分区", "TEXT")); // 汇总时末尾加「分区」列
        }
        // 该分区(或本页)我方最近抓取时间：取各行 updated_at 最大值，供前台显示「分区 X · 更新于 Y」。
        long updatedAt = 0L;
        for (var r : rows) {
            if (r.get("updated_at") instanceof Number num) {
                updatedAt = Math.max(updatedAt, num.longValue());
            }
        }
        return Map.of("rankKey", rankKey, "aggregate", aggregate,
                "page", page, "size", PAGE, "total", total, "updatedAt", updatedAt, "fields", fields, "rows", rows);
    }

    /** 某玩家在所有 rankKey/分区的名次+分数（跨 rankKey 查询）。 */
    @GetMapping("/api/public/slice/player")
    public Map<String, Object> player(@RequestParam String name) {
        var rows = rankStore.playerAcross(name);
        for (var r : rows) {
            Object pi = r.get("partition_idx");
            if (pi instanceof Number n) {
                r.put("partition", n.intValue() + 1);
            }
        }
        return Map.of("name", name, "rows", rows);
    }

    /** 管理：测试邮件通道。 */
    @PostMapping("/api/boards/slice/test-email")
    public Map<String, Object> testEmail(@RequestParam String to) {
        try {
            subs.sendTest(to);
            return Map.of("ok", true, "to", to);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", String.valueOf(e.getMessage()));
        }
    }

    /** 管理：触发分区排行榜数据抓取（force=true 全部重抓）。 */
    @PostMapping("/api/boards/slice/fetch-boards")
    public Map<String, Object> fetchBoards(@RequestParam(required = false, defaultValue = "false") boolean force) {
        int n = rankFetch.fetchAll(force);
        return Map.of("submitted", n);
    }

    /** 管理：单榜「一键全抓」——高并发抓某 rankKey 的全部分区（无需邮箱，后台手动用）。 */
    @PostMapping("/api/boards/slice/fetch-rankkey")
    public Map<String, Object> fetchRankKey(@RequestParam String rankKey) {
        String rk = rankKey == null ? "" : rankKey.trim();
        if (rk.isBlank()) {
            return Map.of("ok", false, "message", "缺少 rankKey");
        }
        int n = rankFetch.fetchRankKeyNowFast(rk);
        return n > 0
                ? Map.of("ok", true, "partitions", n, "message", "已高并发抓取 " + n + " 个分区")
                : Map.of("ok", false, "message", "该榜暂无可抓取的分区来源");
    }

    /** 管理：立即「季末抢数」——最大并发抓取所有隐藏榜全部分区，循环重试直到 200 区全齐。 */
    @PostMapping("/api/boards/slice/season-end")
    public Map<String, Object> seasonEnd() {
        boolean started = rankFetch.seasonEndFetch();
        return Map.of("ok", started,
                "message", started ? "已启动季末抢数（最大并发，后台跑，保证全分区抓齐）" : "已在跑或未启用",
                "status", rankFetch.seasonStatus());
    }

    /** 管理：季末抢数进度（轮询用）。 */
    @GetMapping("/api/boards/slice/season-status")
    public Map<String, Object> seasonStatus() {
        return Map.of("status", rankFetch.seasonStatus());
    }


    /** 在与 rankKey 同分区组(≥0.95)的榜里，挑该分区「可用候选最多」的那个作候选集来源。 */
    private String bestGroupKey(String rankKey, int partition, java.util.Set<Long> visGc) {
        String best = null;
        int bestCount = 0;
        for (String gk : store.sameGroupKeys(rankKey, 0.95)) {
            int cnt = 0;
            for (var gr : rankStore.namedRows(gk, partition)) {
                Long g = toLong(gr.get("gc_id"));
                if (g != null && !visGc.contains(g)) {
                    cnt++;
                }
            }
            if (cnt > bestCount) {
                bestCount = cnt;
                best = gk;
            }
        }
        return best;
    }

    private static Map<String, Object> field(String key, String label, String render) {
        return Map.of("key", key, "label", label, "render", render);
    }

    /**
     * 用每行 gc_id 去同服战力榜(382)批量取战力/VIP/境界，补到 power_number/power_vip/power_realm 列。
     *
     * <p>战力榜每区只抓<b>前 1000</b> —— 玩家若未进其所在区服战力榜前 1000（小区如双线七区常见），
     * 按 gc_id 关联不到任何数据。此时给行打 {@code power_missing=true}，前台据此显示「未上榜」
     * 提示（而非留白让用户以为是抓取故障）。VIP=0 是合法值，必须用标志而非空值区分。
     */
    private void enrichWithPower(List<Map<String, Object>> rows) {
        var ids = new ArrayList<Long>(rows.size());
        for (var r : rows) {
            if (r.get("gc_id") instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        var stats = ids.isEmpty() ? java.util.Map.<Long, long[]>of() : reducedStore.powerStatsByGcId(ids);
        for (var r : rows) {
            long[] s = r.get("gc_id") instanceof Number n ? stats.get(n.longValue()) : null;
            if (s != null) {
                r.put("power_number", s[0]); // 战力
                r.put("power_vip", s[1]);    // VIP等级
                r.put("power_realm", s[2]);  // 境界
            } else {
                r.put("power_missing", true); // 未进本区战力榜前 1000，无法关联
            }
        }
    }

    /**
     * 隐藏玩家身份预测（确定性·递归）：单分区视图里，display=false 且无名的行，按其 last_rank（上赛季名次）
     * 去「上一赛季」rankKey 的同分区同名次找名字；若上赛季那名次也隐藏，再按它的 last_rank 继续往上一季递归。
     * 找到则写 predicted_name + 置信度（随递归深度衰减）+ 来源说明，并标 is_prediction=true。
     */
    private void predictHidden(String rankKey, Integer partition, List<Map<String, Object>> rows, boolean aggregate) {
        if (aggregate || partition == null) {
            return; // 汇总跨分区，last_rank 含义不一，不预测
        }
        var idx = keyIndex();
        if (prevSeasonOf(idx, rankKey) == null) {
            return; // 没设上一赛季，无从递归
        }
        for (var r : rows) {
            boolean hiddenRow = Boolean.FALSE.equals(r.get("display"))
                    && (r.get("char_name") == null || String.valueOf(r.get("char_name")).isBlank());
            if (!hiddenRow) {
                continue;
            }
            Long lastRank = toLong(r.get("last_rank"));
            if (lastRank == null || lastRank <= 0) {
                continue;
            }
            var pred = resolveIdentity(idx, rankKey, partition, lastRank.intValue(), 0);
            if (pred != null) {
                r.put("predicted_name", pred[0]);
                r.put("predicted_confidence", pred[1]);
                r.put("predicted_via", pred[2]);
                r.put("is_prediction", true);
            }
        }
    }

    /** 递归：在 rankKey 的上一赛季同分区找名次=lastRank 的名字；上赛季也隐藏则按其 last_rank 继续。返回 [名字, 置信度, 来源]。 */
    private Object[] resolveIdentity(java.util.Map<String, RankSliceStore.KeyInfo> idx,
            String rankKey, int partition, int lastRank, int depth) {
        if (depth > 8) {
            return null; // 防环
        }
        String prev = prevSeasonOf(idx, rankKey);
        if (prev == null) {
            return null;
        }
        var list = rankStore.rowAt(prev, partition, lastRank);
        if (list.isEmpty()) {
            return null;
        }
        var row = list.get(0);
        Object nm = row.get("char_name");
        if (nm != null && !String.valueOf(nm).isBlank()) {
            double conf = Math.max(0.4, 0.9 - depth * 0.12); // 递归越深越不确定
            String via = "上赛季「" + nameOf(idx, prev) + "」第 " + lastRank + " 名" + (depth > 0 ? "（递归" + (depth + 1) + "层）" : "");
            return new Object[] {String.valueOf(nm), Math.round(conf * 100) / 100.0, via};
        }
        // 上赛季该名次也隐藏 → 用它的 last_rank 继续往上一季递归
        Long deeper = toLong(row.get("last_rank"));
        if (deeper == null || deeper <= 0) {
            return null;
        }
        return resolveIdentity(idx, prev, partition, deeper.intValue(), depth + 1);
    }

    private java.util.Map<String, RankSliceStore.KeyInfo> keyIndex() {
        var m = new java.util.HashMap<String, RankSliceStore.KeyInfo>();
        for (var k : store.listKeys()) {
            m.put(k.rankKey(), k);
        }
        return m;
    }

    private static String prevSeasonOf(java.util.Map<String, RankSliceStore.KeyInfo> idx, String rankKey) {
        var k = idx.get(rankKey);
        if (k == null || k.prevSeason() == null || k.prevSeason().isBlank()) {
            return null;
        }
        return k.prevSeason().trim();
    }

    /** 沿 prev_season 链取最多 max 个「过去赛季」rankKey（新→旧），防环。 */
    private static List<String> seasonChain(java.util.Map<String, RankSliceStore.KeyInfo> idx, String rankKey, int max) {
        var chain = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        seen.add(rankKey);
        String cur = rankKey;
        while (chain.size() < max) {
            String prev = prevSeasonOf(idx, cur);
            if (prev == null || !seen.add(prev)) {
                break;
            }
            chain.add(prev);
            cur = prev;
        }
        return chain;
    }

    private static String nameOf(java.util.Map<String, RankSliceStore.KeyInfo> idx, String rankKey) {
        var k = idx.get(rankKey);
        return (k == null || k.label() == null || k.label().isBlank()) ? rankKey : k.label();
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** rankKey 列表（命名优先显示、静态/动态、来源数、最大分片数）。 */
    @GetMapping("/api/public/slice/keys")
    public Object keys(@RequestParam(required = false) String category) {
        var out = new ArrayList<Map<String, Object>>();
        for (var k : store.listKeys()) {
            if (!matchesCategory(k, category)) {
                continue;
            }
            var m = new java.util.HashMap<String, Object>();
            m.put("rankKey", k.rankKey());
            m.put("label", k.label() == null ? "" : k.label());
            m.put("name", (k.label() == null || k.label().isBlank()) ? k.rankKey() : k.label());
            m.put("dynamic", k.dynamic());
            m.put("visible", k.visible());
            m.put("sources", k.sources());
            m.put("maxSlices", k.maxSlices());
            m.put("lastFetch", k.lastFetch());
            m.put("hidden", k.hidden());
            m.put("prevSeason", k.prevSeason() == null ? "" : k.prevSeason());
            m.put("brawl", k.brawl());
            m.put("sameZone", k.sameZone() == null ? "" : k.sameZone());
            m.put("categories", k.categories());
            m.put("partitionCount", k.partitionCount());
            m.put("seasonEndAt", k.seasonEndAt());
            out.add(m);
        }
        return out;
    }

    private static boolean matchesCategory(RankSliceStore.KeyInfo k, String category) {
        if (category == null || category.isBlank()) {
            return true;
        }
        String q = category.trim().toLowerCase(java.util.Locale.ROOT);
        for (String c : k.categories()) {
            if (c != null && c.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** 原始 rank_slice_group 样本（前 n 个账号的全部 (server, rankKey, partition) 行）。照真实数据观察分组用。 */
    @GetMapping("/api/public/slice/raw")
    public Object rawGroups(@RequestParam(defaultValue = "20") int n) {
        return store.sampleGroupRows(Math.max(1, Math.min(200, n)));
    }

    /** 批量预测进度（身份进度 + 分数进度）+ 某榜最近预测时间。 */
    @GetMapping("/api/public/slice/predict-status")
    public Map<String, Object> predictStatus(@RequestParam(required = false) String rankKey) {
        var st = new java.util.HashMap<String, Object>(prediction.status());
        st.put("aiEnabled", ai.enabled());
        if (rankKey != null && !rankKey.isBlank()) {
            st.put("lastPredictedAt", prediction.lastPredictedAt(rankKey));
        }
        return st;
    }

    /** 重新预测：手动触发「所有隐藏榜」批量预测，受 Turnstile 限流；跑完把前10名邮件发给 email。 */
    @PostMapping("/api/public/slice/predict-now")
    public Map<String, Object> predictNow(@RequestBody Map<String, Object> body) {
        if (!ai.enabled()) {
            return Map.of("ok", false, "message", "AI 预测未启用");
        }
        if (!gtOk(body)) {
            return Map.of("ok", false, "message", "人机验证未通过，请重试");
        }
        String email = String.valueOf(body.getOrDefault("email", "")).trim();
        if (!EMAIL.matcher(email).matches()) {
            return Map.of("ok", false, "message", "邮箱格式不正确");
        }
        if (prediction.isRunning()) {
            return Map.of("ok", false, "message", "预测正在进行中，请稍候再试");
        }
        boolean started = prediction.runBatchAsync(email);
        return Map.of("ok", started, "message", started
                ? "已开始批量预测所有隐藏榜，完成后把预测前10名邮件发到 " + email
                : "预测启动失败（可能已在运行）");
    }

    /** 两两 rankKey 的分区一致率（same/total≈1 即同分区分组，候选集可互查）。诊断/分组观察用。 */
    @GetMapping("/api/public/slice/groups")
    public Object groups() {
        var idx = keyIndex();
        var out = new ArrayList<Map<String, Object>>();
        for (var g : store.groupAgreement()) {
            double rate = g.total() == 0 ? 0 : (double) g.same() / g.total();
            out.add(Map.of(
                    "a", g.a(), "aName", nameOf(idx, g.a()),
                    "b", g.b(), "bName", nameOf(idx, g.b()),
                    "same", g.same(), "total", g.total(),
                    "rate", Math.round(rate * 1000) / 1000.0));
        }
        return out;
    }

    /** 某 rankKey 的 200 格覆盖热力（coverage[i] = 覆盖分片 i 的来源数）。 */
    @GetMapping("/api/public/slice/grid")
    public Map<String, Object> grid(@RequestParam String rankKey) {
        int[] cov = store.coverageGrid(rankKey);
        int slices = store.listKeys().stream()
                .filter(k -> k.rankKey().equals(rankKey))
                .findFirst()
                .map(RankSliceStore.KeyInfo::partitionCount)
                .orElse(RankSliceStore.MAX_SLICES);
        var list = new ArrayList<Integer>(slices);
        int max = 0;
        for (int i = 0; i < Math.min(slices, cov.length); i++) {
            int c = cov[i];
            list.add(c);
            if (c > max) {
                max = c;
            }
        }
        // 注意：先 findFirst 再 map——若先 map(label) 而 label 为 null，Stream.findFirst() 会 NPE。
        String label = store.listKeys().stream()
                .filter(k -> k.rankKey().equals(rankKey))
                .findFirst().map(RankSliceStore.KeyInfo::label).orElse(null);
        return Map.of("rankKey", rankKey, "label", label == null ? "" : label,
                "slices", slices, "maxSource", max, "coverage", list);
    }

    /** 整体进度 + 抓取器状态。 */
    @GetMapping("/api/public/slice/progress")
    public Map<String, Object> progress() {
        var p = store.progress();
        return Map.of(
                "sources", p.sources(), "servers", p.servers(),
                "rankKeys", p.rankKeys(), "metaKeys", p.metaKeys(), "lastUpdate", p.lastUpdate(),
                "fetch", fetch.status(),
                "boards", rankFetch.status());
    }

    /** 管理：给 rankKey 命名。 */
    @PostMapping("/api/boards/slice/label")
    public Map<String, Object> label(@RequestBody Map<String, String> body) {
        String rankKey = body.get("rankKey");
        store.setLabel(rankKey, body.getOrDefault("label", ""));
        return Map.of("ok", true, "rankKey", rankKey);
    }

    /** 管理：设置静态/动态（动态=每 30 分钟刷新排行榜数据）。 */
    @PostMapping("/api/boards/slice/dynamic")
    public Map<String, Object> dynamic(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        boolean dyn = Boolean.TRUE.equals(body.get("dynamic")) || "true".equals(String.valueOf(body.get("dynamic")));
        store.setDynamic(rankKey, dyn);
        return Map.of("ok", true, "rankKey", rankKey, "dynamic", dyn);
    }

    /** 管理：标记/取消该 rankKey 为隐藏赛季榜（隐藏玩家按 last_rank 递归预测身份）。 */
    @PostMapping("/api/boards/slice/hidden")
    public Map<String, Object> hidden(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        boolean hid = Boolean.TRUE.equals(body.get("hidden")) || "true".equals(String.valueOf(body.get("hidden")));
        store.setHidden(rankKey, hid);
        return Map.of("ok", true, "rankKey", rankKey, "hidden", hid);
    }

    /** 管理：标记/取消该 rankKey 为乱斗榜（隐藏玩家额外 AI 预测分数）。 */
    @PostMapping("/api/boards/slice/brawl")
    public Map<String, Object> brawl(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        boolean b = Boolean.TRUE.equals(body.get("brawl")) || "true".equals(String.valueOf(body.get("brawl")));
        store.setBrawl(rankKey, b);
        return Map.of("ok", true, "rankKey", rankKey, "brawl", b);
    }

    /** 管理：设置分区榜分类标签（多选 + 自定义，JSON 持久化）。 */
    @PostMapping("/api/boards/slice/categories")
    public Map<String, Object> categories(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        var cats = new ArrayList<String>();
        Object raw = body.get("categories");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    cats.add(String.valueOf(item));
                }
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split("[,，]")) {
                cats.add(part);
            }
        }
        store.setCategories(rankKey, cats);
        return Map.of("ok", true, "rankKey", rankKey, "categories", cats);
    }

    /** 管理：设置该 rankKey 的「上一赛季」rankKey（空=清除）。 */
    @PostMapping("/api/boards/slice/prev-season")
    public Map<String, Object> prevSeason(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        String prev = body.get("prevSeason") == null ? "" : String.valueOf(body.get("prevSeason"));
        store.setPrevSeason(rankKey, prev);
        return Map.of("ok", true, "rankKey", rankKey, "prevSeason", prev);
    }

    /** 管理：设置该 rankKey 实际分区数量（1-200）。 */
    @PostMapping("/api/boards/slice/partition-count")
    public Map<String, Object> partitionCount(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        int n = body.get("partitionCount") instanceof Number num ? num.intValue()
                : parseInt(String.valueOf(body.getOrDefault("partitionCount", "200")), 200);
        n = Math.max(1, Math.min(RankSliceStore.MAX_SLICES, n));
        store.setPartitionCount(rankKey, n);
        return Map.of("ok", true, "rankKey", rankKey, "partitionCount", n);
    }

    /** 管理：设置该 rankKey 赛季/活动结束时间（epoch ms；0=清除）。 */
    @PostMapping("/api/boards/slice/season-end-at")
    public Map<String, Object> seasonEndAt(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        long v = body.get("seasonEndAt") instanceof Number num ? num.longValue()
                : parseLong(String.valueOf(body.getOrDefault("seasonEndAt", "0")), 0L);
        v = Math.max(0L, v);
        store.setSeasonEndAt(rankKey, v);
        return Map.of("ok", true, "rankKey", rankKey, "seasonEndAt", v);
    }

    /** 管理：设置该 rankKey 的「同区榜」rankKey（空=清除，回落自动判定）。覆盖隐藏预测候选源的自动重叠判定。 */
    @PostMapping("/api/boards/slice/same-zone")
    public Map<String, Object> sameZone(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        String sz = body.get("sameZone") == null ? "" : String.valueOf(body.get("sameZone"));
        store.setSameZone(rankKey, sz);
        return Map.of("ok", true, "rankKey", rankKey, "sameZone", sz);
    }

    /** 管理：设置该 rankKey 是否在前台展示。 */
    @PostMapping("/api/boards/slice/visible")
    public Map<String, Object> visible(@RequestBody Map<String, Object> body) {
        String rankKey = String.valueOf(body.get("rankKey"));
        boolean vis = !Boolean.FALSE.equals(body.get("visible")) && !"false".equals(String.valueOf(body.get("visible")));
        store.setVisible(rankKey, vis);
        return Map.of("ok", true, "rankKey", rankKey, "visible", vis);
    }

    /** 管理：全量重抓分区榜（<b>不清空</b>，每个分区抓到后整段覆盖旧数据，避免清空后出现空窗）。 */
    @PostMapping("/api/boards/slice/reset")
    public Map<String, Object> reset() {
        int submitted = rankFetch.fetchAll(true);
        return Map.of("ok", true, "cleared", 0, "resubmitted", submitted,
                "message", "已提交 " + submitted + " 个账号来源全量重抓（覆盖，不清空）");
    }

    /** 管理：清空全部隐藏预测并立即重跑批量（换了预测算法后用）。 */
    @PostMapping("/api/boards/slice/repredict")
    public Map<String, Object> repredict() {
        long cleared = prediction.clearAndRerun();
        return Map.of("ok", true, "cleared", cleared, "message", "已清空 " + cleared + " 条旧预测并开始重跑");
    }

    /** 管理：手动触发全量重抓分区获取。 */
    @PostMapping("/api/boards/slice/refetch")
    public Map<String, Object> refetch() {
        int n = fetch.enqueueAll();
        return Map.of("enqueued", n);
    }

    private static int parseInt(String s, int dft) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return dft;
        }
    }

    private static long parseLong(String s, long dft) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return dft;
        }
    }
}
