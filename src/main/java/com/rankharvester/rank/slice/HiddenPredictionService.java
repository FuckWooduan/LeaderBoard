package com.rankharvester.rank.slice;

import com.rankharvester.rank.store.PlayerMomentumStore;
import com.rankharvester.rank.store.ReducedRankStore;
import com.rankharvester.web.AiPredictor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 隐藏玩家预测的核心服务：单分区预测（确定性身份 + AI 身份/分数）、批量预测（所有隐藏榜的所有分区）、
 * 每小时自动跑、手动触发跑完发邮件、进度跟踪（身份进度 + 分数进度）。结果落 {@link HiddenPredictionStore}。
 */
@Service
public class HiddenPredictionService {

    private static final Logger log = LoggerFactory.getLogger(HiddenPredictionService.class);
    private static final int MAX_CANDIDATES = 60; // 候选上限（并集了所有同分区组榜，放宽以减少空白隐藏槽）
    private static final int SEASON_DEPTH = 5;    // 历史回溯赛季数
    private static final String PROGRESS_KEY = "rh:hidden:progress"; // 进度持久化（跨重启不归零）
    private static final long STALE_MS = 45 * 60_000L;              // 预测过期阈值：超过则启动补跑

    private final RankSliceStore store;
    private final PartitionRankStore rankStore;
    private final ReducedRankStore reducedStore;
    private final AiPredictor ai;
    private final HiddenPredictionStore predStore;
    private final SliceSubscriptionService subs;
    private final StringRedisTemplate redis;
    private final PlayerMomentumStore momentumStore;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger total = new AtomicInteger(0);      // 总隐藏槽位
    private final AtomicInteger identityDone = new AtomicInteger(0);
    private final AtomicInteger scoreDone = new AtomicInteger(0);
    private volatile long startedAt = 0;
    private volatile long finishedAt = 0;
    private volatile String phase = "idle"; // idle / running / done / error
    private volatile String lastError = "";

    public HiddenPredictionService(RankSliceStore store, PartitionRankStore rankStore,
            ReducedRankStore reducedStore, AiPredictor ai, HiddenPredictionStore predStore,
            SliceSubscriptionService subs, StringRedisTemplate redis, PlayerMomentumStore momentumStore) {
        this.store = store;
        this.rankStore = rankStore;
        this.reducedStore = reducedStore;
        this.ai = ai;
        this.predStore = predStore;
        this.subs = subs;
        this.redis = redis;
        this.momentumStore = momentumStore;
    }

    // ───────────────────────── 状态 / 进度 ─────────────────────────

    public Map<String, Object> status() {
        int t = total.get();
        // 下一次自动预测 ≈ 上次完成 + 1 小时（@Scheduled fixedDelay=1h）；正在跑或尚未跑过则为 0，前端兜底。
        long nextPredictAt = running.get() ? 0L : (finishedAt > 0 ? finishedAt + 3600_000L : 0L);
        return Map.ofEntries(
                Map.entry("running", running.get()),
                Map.entry("phase", phase),
                Map.entry("total", t),
                Map.entry("identityDone", identityDone.get()),
                Map.entry("scoreDone", scoreDone.get()),
                Map.entry("identityPercent", t > 0 ? Math.round(identityDone.get() * 1000.0 / t) / 10.0 : 0),
                Map.entry("scorePercent", t > 0 ? Math.round(scoreDone.get() * 1000.0 / t) / 10.0 : 0),
                Map.entry("startedAt", startedAt),
                Map.entry("finishedAt", finishedAt),
                Map.entry("nextPredictAt", nextPredictAt),
                Map.entry("error", lastError));
    }

    public boolean isRunning() {
        return running.get();
    }

    public long lastPredictedAt(String rankKey) {
        return predStore.lastPredictedAt(rankKey);
    }

    /** 清空全部已落库预测（换算法后用），并立即重跑批量。返回清掉的条数。 */
    public long clearAndRerun() {
        long cleared = predStore.clearAll();
        runBatchAsync(null);
        return cleared;
    }

    // ───────────────────────── 触发 ─────────────────────────

    /** 每小时自动批量预测（与上次至少隔 5 分钟、且当前不在跑）。 */
    @Scheduled(fixedDelay = 60 * 60_000L, initialDelay = 8 * 60_000L)
    void scheduled() {
        if (!ai.enabled()) {
            return;
        }
        runBatchAsync(null);
    }

    /** 异步批量跑；email 非空则跑完发前10。已在跑则忽略。返回是否成功启动。 */
    public boolean runBatchAsync(String email) {
        if (!ai.enabled() || !running.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("hidden-batch").start(() -> {
            try {
                runBatch(email);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private void runBatch(String email) {
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        phase = "running";
        lastError = "";
        total.set(0);
        identityDone.set(0);
        scoreDone.set(0);
        var idx = keyIndex();
        // 所有标记隐藏的榜
        var hiddenKeys = new ArrayList<String>();
        for (var k : idx.values()) {
            if (k.hidden()) {
                hiddenKeys.add(k.rankKey());
            }
        }
        log.info("[HiddenBatch] 开始批量预测，隐藏榜 {} 个", hiddenKeys.size());
        var emailedKey = hiddenKeys.isEmpty() ? null : hiddenKeys.get(0);
        try {
            for (String rankKey : hiddenKeys) {
                for (int p = 0; p < RankSliceStore.MAX_SLICES; p++) {
                    var preds = predictPartition(idx, rankKey, p);
                    if (preds == null) {
                        continue; // 该分区无隐藏行
                    }
                    if (!preds.isEmpty()) {
                        predStore.replacePartition(rankKey, p, preds);
                    }
                }
                saveProgress(); // 每个隐藏榜跑完持久化一次：中途被部署打断也能保留进度
            }
            phase = "done";
            finishedAt = System.currentTimeMillis();
            saveProgress();
            log.info("[HiddenBatch] 完成：身份 {}/{}，分数 {}/{}", identityDone.get(), total.get(),
                    scoreDone.get(), total.get());
            if (email != null && !email.isBlank() && emailedKey != null) {
                try {
                    subs.sendPredictionDigest(email, emailedKey, predStore.topByScore(emailedKey, 10),
                            nameOf(idx, emailedKey), identityDone.get(), scoreDone.get(), total.get());
                } catch (RuntimeException e) {
                    log.warn("[HiddenBatch] 预测摘要邮件发送失败 {}: {}", email, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            phase = "error";
            finishedAt = System.currentTimeMillis();
            lastError = String.valueOf(e.getMessage());
            saveProgress();
            log.warn("[HiddenBatch] 批量预测异常: {}", e.getMessage());
        }
    }

    // 进度持久化（跨重启不归零）+ 启动恢复 + 过期补跑 ─────────────────────────────

    /** 把进度计数器写入 Redis，重启后可恢复（否则内存计数器归零、前端进度条变空）。失败忽略。 */
    private void saveProgress() {
        try {
            redis.opsForValue().set(PROGRESS_KEY, String.join("|",
                    String.valueOf(total.get()), String.valueOf(identityDone.get()),
                    String.valueOf(scoreDone.get()), String.valueOf(startedAt),
                    String.valueOf(finishedAt), phase));
        } catch (RuntimeException e) {
            log.warn("[HiddenBatch] 进度持久化失败（忽略）: {}", e.toString());
        }
    }

    /** 启动时恢复上次进度；预测过期(>45min)或从未跑过则 2 分钟后自动补跑一次（避免频繁部署饿死每小时批量、进度条变空）。 */
    @jakarta.annotation.PostConstruct
    void restoreProgressAndCatchUp() {
        try {
            String s = redis.opsForValue().get(PROGRESS_KEY);
            if (s != null && !s.isBlank()) {
                String[] f = s.split("\\|", -1);
                if (f.length >= 6) {
                    total.set(parseIntSafe(f[0]));
                    identityDone.set(parseIntSafe(f[1]));
                    scoreDone.set(parseIntSafe(f[2]));
                    startedAt = parseLongSafe(f[3]);
                    finishedAt = parseLongSafe(f[4]);
                    phase = "running".equals(f[5]) ? "done" : f[5]; // 重启后不可能仍在跑
                    log.info("[HiddenBatch] 恢复进度：身份 {}/{}", identityDone.get(), total.get());
                }
            }
        } catch (Exception e) {
            log.warn("[HiddenBatch] 进度恢复失败（忽略）: {}", e.toString());
        }
        if (ai.enabled() && (finishedAt <= 0 || System.currentTimeMillis() - finishedAt > STALE_MS)) {
            Thread.ofVirtual().name("hidden-catchup").start(() -> {
                try {
                    Thread.sleep(120_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) {
                    log.info("[HiddenBatch] 启动补跑：预测过期，自动跑一次");
                    runBatchAsync(null);
                }
            });
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    // ───────────────────────── 单分区预测 ─────────────────────────

    /**
     * 预测某 (rankKey, 分区) 的隐藏玩家：确定性身份(上赛季递归) + AI 身份/分数。
     * 返回 null=该分区没有隐藏行；空 list=有隐藏行但都预测不出。进度计数在内部累加。
     */
    public List<HiddenPredictionStore.Pred> predictPartition(Map<String, RankSliceStore.KeyInfo> idx,
            String rankKey, int partition) {
        var rows = rankStore.page(rankKey, partition, 1, 1000);
        // 隐藏槽位
        var hidden = new ArrayList<Map<String, Object>>();
        var visGc = new HashSet<Long>();
        for (var r : rows) {
            boolean hiddenRow = Boolean.FALSE.equals(r.get("display"))
                    && (r.get("char_name") == null || String.valueOf(r.get("char_name")).isBlank());
            if (hiddenRow) {
                hidden.add(r);
            } else {
                Long g = toLong(r.get("gc_id"));
                if (g != null && r.get("char_name") != null) {
                    visGc.add(g);
                }
            }
        }
        if (hidden.isEmpty()) {
            return null;
        }
        var ki = idx.get(rankKey);
        boolean brawl = ki != null && ki.brawl();
        long now = System.currentTimeMillis();
        total.addAndGet(hidden.size());

        // 候选集 = 本季真隐藏的人（同分区组公开榜 − 已可见）。身份的 ground truth：天然排除本季可见的人、不重复。
        var candByGc = candidateRows(rankKey, partition, visGc);
        if (candByGc.isEmpty()) {
            return List.of(); // 无候选来源，识别不出
        }
        // 1) 保守确定性：候选在「上一赛季」的名次 唯一 == 槽位 last_rank 时锁定（干净情况，免费高置信）
        var det = detMatch(idx, rankKey, hidden, candByGc);
        var usedGc = new HashSet<Long>();
        for (var v : det.values()) {
            usedGc.add((Long) v[3]);
        }
        // 2) 候选（含逐赛季 {名次,分数} 历史）喂 AI
        var candidates = buildCandidates(idx, rankKey, candByGc, usedGc);
        // 3) 送 AI 的槽位：乱斗榜全部(要分数)，否则只送确定性没锁定的
        var aiSlots = new ArrayList<AiPredictor.Slot>();
        for (var h : hidden) {
            int rank = intOf(h.get("rank"));
            if (!brawl && det.containsKey(rank)) {
                continue;
            }
            Long lr = toLong(h.get("last_rank"));
            aiSlots.add(new AiPredictor.Slot(rank, lr, buildSlotChain(idx, rankKey, partition, lr)));
        }
        var known = new LinkedHashMap<Integer, String>(); // 已确定的槽位身份，AI 不要改、不要重用这些候选
        for (var e : det.entrySet()) {
            known.put(e.getKey(), (String) e.getValue()[0]);
        }
        var anchors = new ArrayList<AiPredictor.Anchor>();
        if (brawl) {
            for (var r : rows) {
                Long sc = toLong(r.get("score_number"));
                int rk = intOf(r.get("rank"));
                if (rk > 0 && sc != null && r.get("char_name") != null
                        && !String.valueOf(r.get("char_name")).isBlank()) {
                    anchors.add(new AiPredictor.Anchor(rk, sc));
                }
            }
        }
        var aiByRank = new LinkedHashMap<Integer, AiPredictor.Prediction>();
        if (!aiSlots.isEmpty()) {
            for (var p : ai.predictHidden(nameOf(idx, rankKey), partition, aiSlots, candidates, anchors, brawl, known)) {
                aiByRank.putIfAbsent(p.rank(), p);
            }
        }

        // 4) 合并：确定性身份优先；其次 AI；都没有则「沿 last_rank 同分区逐赛季递归」找名（用户要的递归跟进）。
        var merged = new ArrayList<HiddenPredictionStore.Pred>();
        for (var h : hidden) {
            int rank = intOf(h.get("rank"));
            Object[] d = det.get(rank);
            AiPredictor.Prediction aip = aiByRank.get(rank);
            String name = d != null ? (String) d[0] : (aip != null ? aip.name() : null);
            double conf;
            String via;
            String reason;
            if (name != null) {
                conf = d != null ? (double) d[1] : aip.confidence();
                via = d != null ? "det" : "AI";
                reason = d != null ? (String) d[2] : (aip != null ? aip.reason() : "");
            } else {
                // 递归：沿本槽 last_rank 在同分区逐赛季回溯，取最近一季公开的实名（partition-aware，不跨区误配）。
                Long lr = toLong(h.get("last_rank"));
                Object[] rec = (lr != null && lr > 0) ? resolveIdentity(idx, rankKey, partition, lr.intValue(), 0) : null;
                if (rec == null) {
                    continue; // 递归也找不到 → 留给后面的候选兜底 fillRemaining
                }
                name = (String) rec[0];
                conf = Math.min(0.55, (double) rec[1]); // 递归推断置信封顶，别和 det 抢
                via = "recurse";
                reason = "递归同分区：" + rec[2];
            }
            Long score = (brawl && aip != null) ? aip.score() : null;
            double scoreConf = (brawl && aip != null) ? aip.scoreConfidence() : 0.0;
            merged.add(new HiddenPredictionStore.Pred(rank, name, conf, score, scoreConf, via, reason, now));
        }
        // 5) 同分区身份去重：同一身份只保留最强主张（det 优先，其次置信度），重复者剔除身份
        //    （如「上赛季都是第一」导致两个名次撞成同一人）。乱斗榜保留其预测分（身份置空）。
        var out = dedupIdentities(merged);
        for (var p : out) {
            if (p.name() != null) {
                identityDone.incrementAndGet();
            }
            if (p.score() != null) {
                scoreDone.incrementAndGet();
            }
        }
        // 6) 兜底显示：仍空着的隐藏槽位用「未被使用的候选人」按战力强→弱补给名次靠前的空槽，低置信「推测」。
        //    用户要求：即使不准也要显示，不留「隐藏/未上榜」。这些仅用于展示，不计入识别进度。
        return fillRemaining(out, hidden, candidates, now);
    }

    /** 把还没有任何预测的隐藏槽位用未使用候选人兜底填上（低置信「推测」，仅供展示）。 */
    private static List<HiddenPredictionStore.Pred> fillRemaining(List<HiddenPredictionStore.Pred> out,
            List<Map<String, Object>> hidden, List<AiPredictor.Candidate> candidates, long now) {
        var usedNames = new HashSet<String>();
        var hasEntry = new HashSet<Integer>();
        for (var p : out) {
            if (p.name() != null) {
                usedNames.add(p.name());
            }
            hasEntry.add(p.rank());
        }
        var emptyRanks = new ArrayList<Integer>();
        for (var h : hidden) {
            int r = intOf(h.get("rank"));
            if (!hasEntry.contains(r)) {
                emptyRanks.add(r);
            }
        }
        // 仍空着的隐藏槽（连同其 last_rank，用于占位名）。
        var emptySlots = new ArrayList<Map<String, Object>>();
        for (var h : hidden) {
            if (!hasEntry.contains(intOf(h.get("rank")))) {
                emptySlots.add(h);
            }
        }
        if (emptySlots.isEmpty()) {
            return out;
        }
        emptySlots.sort((a, b) -> Integer.compare(intOf(a.get("rank")), intOf(b.get("rank"))));
        var pool = new ArrayList<AiPredictor.Candidate>();
        for (var c : candidates) {
            if (c.name() != null && !c.name().isBlank() && !usedNames.contains(c.name())) {
                pool.add(c);
            }
        }
        pool.sort((a, b) -> Long.compare(b.power() == null ? 0 : b.power(), a.power() == null ? 0 : a.power()));
        var result = new ArrayList<>(out);
        int pi = 0;
        // 用户要求：每个隐藏槽都要出预测、绝不留空白。先用未用候选(按战力)补，候选耗尽再用「上季名次」占位。
        for (var h : emptySlots) {
            int rank = intOf(h.get("rank"));
            if (pi < pool.size()) {
                var c = pool.get(pi++);
                result.add(new HiddenPredictionStore.Pred(rank, c.name(), 0.2, null, 0.0, "guess",
                        "候选兜底推测（仅供参考）", now));
            } else {
                Long lr = toLong(h.get("last_rank"));
                String ph = (lr != null && lr > 0) ? "上季第" + lr + "名" : "隐匿强者";
                result.add(new HiddenPredictionStore.Pred(rank, ph, 0.1, null, 0.0, "placeholder",
                        "暂无足够线索，按上季名次占位（仅供参考）", now));
            }
        }
        result.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        return result;
    }

    /**
     * 同分区身份去重：相同 predicted_name 只保留最强的一条（det 优先，其次置信度高）。其余「重复身份」剔除身份——
     * 乱斗榜保留其预测分（身份置空，前端显示「🔒隐藏 + 预测分」），非乱斗直接丢弃。
     * 解决两个名次撞成同一人（例如上赛季都是第一名）的问题：宁可少报身份，也不重复误报。
     */
    private static List<HiddenPredictionStore.Pred> dedupIdentities(List<HiddenPredictionStore.Pred> merged) {
        var sorted = new ArrayList<>(merged);
        sorted.sort((a, b) -> {
            boolean ad = "det".equals(a.via());
            boolean bd = "det".equals(b.via());
            if (ad != bd) {
                return ad ? -1 : 1; // det 优先
            }
            return Double.compare(b.confidence(), a.confidence()); // 再按置信度降序
        });
        var takenName = new HashSet<String>();
        var kept = new ArrayList<HiddenPredictionStore.Pred>();
        for (var p : sorted) {
            if ("placeholder".equals(p.via())) {
                kept.add(p); // 占位项（按上季名次）不参与身份去重，保证每个隐藏槽都有内容、绝不变回空白
                continue;
            }
            if (p.name() != null && takenName.add(p.name())) {
                kept.add(p); // 该身份首次出现 → 保留
            } else if (p.score() != null) {
                // 身份重复但有预测分（乱斗榜）→ 去掉身份、仅留分数
                kept.add(new HiddenPredictionStore.Pred(p.rank(), null, 0.0,
                        p.score(), p.scoreConfidence(), "AI", "身份重复已剔除，仅保留预测分", p.predictedAt()));
            }
            // 否则（身份重复且无分数）整条丢弃
        }
        kept.sort((a, b) -> Integer.compare(a.rank(), b.rank())); // 还原名次顺序
        return kept;
    }

    /** 读路径用：对已落库的 rank→Pred 做身份去重，返回去重后的 rank→Pred。 */
    private static Map<Integer, HiddenPredictionStore.Pred> dedupStored(
            Map<Integer, HiddenPredictionStore.Pred> raw) {
        var out = new LinkedHashMap<Integer, HiddenPredictionStore.Pred>();
        for (var p : dedupIdentities(new ArrayList<>(raw.values()))) {
            out.put(p.rank(), p);
        }
        return out;
    }

    /**
     * 把预测结果合并进 board 行（供读路径展示）：优先用已落库的批量预测（含 AI 身份/分数），
     * 没有的话用实时确定性身份兜底（last_rank→上赛季，便宜、不调 AI）。仅单分区。
     */
    public void applyPredictions(String rankKey, Integer partition, List<Map<String, Object>> rows,
            boolean aggregate) {
        if (aggregate || partition == null) {
            return;
        }
        // 读路径同样去重：即使已落库的旧预测含重复身份，也在展示时消除（零 AI 成本，立即生效）。
        var stored = dedupStored(predStore.forPartition(rankKey, partition));
        var allHidden = new ArrayList<Map<String, Object>>();
        var unstored = new ArrayList<Map<String, Object>>();
        var visGc = new HashSet<Long>();
        for (var r : rows) {
            boolean hiddenRow = Boolean.FALSE.equals(r.get("display"))
                    && (r.get("char_name") == null || String.valueOf(r.get("char_name")).isBlank());
            if (!hiddenRow) {
                Long g = toLong(r.get("gc_id"));
                if (g != null && r.get("char_name") != null) {
                    visGc.add(g);
                }
                continue;
            }
            allHidden.add(r);
            var p = stored.get(intOf(r.get("rank")));
            if (p != null) {
                r.put("predicted_name", p.name());
                r.put("predicted_confidence", p.confidence());
                r.put("predicted_source", "det".equals(p.via()) ? "det" : "AI");
                r.put("predicted_via", p.reason());
                r.put("is_prediction", true);
                // 预测分数已彻底删除（不准）：只展示身份预测，不再输出 predicted_score。
            } else {
                unstored.add(r);
            }
        }
        if (allHidden.isEmpty()) {
            return;
        }
        // 候选集（=本季真隐藏的人）：既用于确定性兜底，也用于给预测行补 区服/等级/战队/gc_id。
        var candByGc = candidateRows(rankKey, partition, visGc);
        var byName = new HashMap<String, Map<String, Object>>();
        for (var crow : candByGc.values()) {
            byName.put(String.valueOf(crow.get("char_name")), crow);
        }
        // 兜底：候选驱动的保守确定性 det（不调 AI），给还没批量预测到的隐藏行即时显示身份。
        if (!unstored.isEmpty() && !candByGc.isEmpty()) {
            var det = detMatch(keyIndex(), rankKey, allHidden, candByGc);
            for (var r : unstored) {
                var d = det.get(intOf(r.get("rank")));
                if (d != null) {
                    r.put("predicted_name", d[0]);
                    r.put("predicted_confidence", d[1]);
                    r.put("predicted_source", "det");
                    r.put("predicted_via", d[2]);
                    r.put("is_prediction", true);
                }
            }
        }
        // 给每个「已预测出身份」的隐藏行补该候选玩家的 区服/等级/战队/gc_id
        // （gc_id 供随后的 enrichWithPower 填 战力/VIP/境界；board() 已保证此函数在 enrichWithPower 之前调用）。
        for (var r : allHidden) {
            Object pn = r.get("predicted_name");
            if (pn == null) {
                continue;
            }
            var crow = byName.get(String.valueOf(pn));
            if (crow != null) {
                r.put("gc_id", crow.get("gc_id"));
                r.put("login_id", crow.get("login_id"));
                r.put("lv", crow.get("lv"));
                r.put("team_name", crow.get("team_name"));
            }
        }
        // 读路径 100% 兜底（全程不调 AI，立即生效）：任何仍无预测的隐藏行——先递归同分区找名，
        // 再用「上季名次」占位，绝不留空白。用户要求：每个隐藏槽都要有数据。
        var idxForRec = keyIndex();
        var usedNames = new HashSet<String>();
        for (var r : allHidden) {
            Object pn = r.get("predicted_name");
            if (pn != null) {
                usedNames.add(String.valueOf(pn));
            }
        }
        for (var r : allHidden) {
            if (r.get("predicted_name") != null) {
                continue;
            }
            Long lr = toLong(r.get("last_rank"));
            Object[] rec = (lr != null && lr > 0)
                    ? resolveIdentity(idxForRec, rankKey, partition, lr.intValue(), 0) : null;
            if (rec != null && usedNames.add((String) rec[0])) {
                r.put("predicted_name", rec[0]);
                r.put("predicted_confidence", Math.min(0.55, (double) rec[1]));
                r.put("predicted_source", "recurse");
                r.put("predicted_via", "递归同分区：" + rec[2]);
                r.put("is_prediction", true);
                var crow = byName.get(String.valueOf(rec[0]));
                if (crow != null) {
                    r.put("gc_id", crow.get("gc_id"));
                    r.put("login_id", crow.get("login_id"));
                    r.put("lv", crow.get("lv"));
                    r.put("team_name", crow.get("team_name"));
                }
            } else {
                r.put("predicted_name", (lr != null && lr > 0) ? "上季第" + lr + "名" : "隐匿强者");
                r.put("predicted_confidence", 0.1);
                r.put("predicted_source", "placeholder");
                r.put("predicted_via", "暂无足够线索，按上季名次占位（仅供参考）");
                r.put("is_prediction", true);
            }
        }
    }

    /**
     * 候选集 gc_id → 行：与 rankKey 同分区组(手动同区 + 自动≥0.95)的<b>所有</b>榜里、该分区命名且未在本季可见的玩家并集。
     * 这是本季真隐藏的人。并集（而非只取单个最佳榜）能把更多隐藏玩家纳入候选，显著减少「空白隐藏槽」。
     */
    private LinkedHashMap<Long, Map<String, Object>> candidateRows(String rankKey, int partition, Set<Long> visGc) {
        var out = new LinkedHashMap<Long, Map<String, Object>>();
        var groupKeys = new ArrayList<String>();
        String manual = store.sameZoneOf(rankKey);
        if (manual != null && !manual.isBlank() && !manual.trim().equals(rankKey)) {
            groupKeys.add(manual.trim());
        }
        for (String gk : store.sameGroupKeys(rankKey, 0.95)) {
            if (!groupKeys.contains(gk)) {
                groupKeys.add(gk);
            }
        }
        for (String gk : groupKeys) {
            for (var gr : rankStore.namedRows(gk, partition)) {
                Long g = toLong(gr.get("gc_id"));
                if (g == null || visGc.contains(g) || out.containsKey(g)) {
                    continue;
                }
                out.put(g, gr);
                if (out.size() >= MAX_CANDIDATES) {
                    return out;
                }
            }
        }
        return out;
    }

    /** 给候选补战力/VIP/境界 + 逐赛季 {名次,分数} 历史（沿赛季链，缺席的赛季不出现）。 */
    private List<AiPredictor.Candidate> buildCandidates(Map<String, RankSliceStore.KeyInfo> idx,
            String rankKey, LinkedHashMap<Long, Map<String, Object>> candByGc, Set<Long> excludeGc) {
        var stats = reducedStore.powerStatsByGcId(new ArrayList<>(candByGc.keySet()));
        var mom = momentumStore.momentumByGcId(candByGc.keySet()); // gc → [expDelta, lvDelta, hours]
        var chain = seasonChain(idx, rankKey, SEASON_DEPTH);
        var out = new ArrayList<AiPredictor.Candidate>();
        for (var e : candByGc.entrySet()) {
            long gc = e.getKey();
            if (excludeGc.contains(gc)) {
                continue; // 该候选已被确定性(det)锁定到某槽位 → 不再喂给 AI，避免 AI 把同一人重复预测给别的名次
            }
            long[] s = stats.get(gc);
            long[] m = mom.get(gc); // 近期经验/等级动量（活跃度信号），无则 null
            var history = new ArrayList<AiPredictor.HistPoint>();
            for (String season : chain) {
                Integer r = rankStore.rankByGcId(season, gc);
                Long sc = rankStore.scoreByGcId(season, gc);
                if (r != null || (sc != null && sc > 0)) { // 那季确实打了才记
                    history.add(new AiPredictor.HistPoint(r, sc));
                }
            }
            out.add(new AiPredictor.Candidate(String.valueOf(e.getValue().get("char_name")),
                    s != null ? s[0] : null, s != null ? s[1] : null, s != null ? s[2] : null, history,
                    m != null ? m[0] : null, m != null ? m[1] : null, m != null ? m[2] : null));
        }
        return out;
    }

    /**
     * 保守确定性匹配：候选在「上一赛季」的名次 唯一 == 隐藏槽位的 last_rank 时才锁定。
     * 候选侧/槽位侧任一名次有冲突(多人同名次/多个槽位同 last_rank)则不锁，留给 AI。
     * 返回 槽位名次 → [name, conf, via, gcId]。
     */
    private Map<Integer, Object[]> detMatch(Map<String, RankSliceStore.KeyInfo> idx, String rankKey,
            List<Map<String, Object>> hidden, LinkedHashMap<Long, Map<String, Object>> candByGc) {
        var out = new LinkedHashMap<Integer, Object[]>();
        String prev = prevSeasonOf(idx, rankKey);
        if (prev == null) {
            return out;
        }
        // 候选上赛季名次 → [gc, name]；冲突名次记入 collided
        var prevRankToCand = new LinkedHashMap<Integer, Object[]>();
        var collided = new HashSet<Integer>();
        for (var e : candByGc.entrySet()) {
            Integer pr = rankStore.rankByGcId(prev, e.getKey());
            if (pr == null) {
                continue; // 该候选没打上赛季 → 不能确定性锁定
            }
            if (prevRankToCand.containsKey(pr)) {
                collided.add(pr);
            } else {
                prevRankToCand.put(pr, new Object[] {e.getKey(), String.valueOf(e.getValue().get("char_name"))});
            }
        }
        // 槽位 last_rank 计数（多个槽位同 last_rank 则该值有歧义）
        var slotLastCount = new HashMap<Integer, Integer>();
        for (var h : hidden) {
            Long lr = toLong(h.get("last_rank"));
            if (lr != null && lr > 0) {
                slotLastCount.merge(lr.intValue(), 1, Integer::sum);
            }
        }
        for (var h : hidden) {
            Long lr = toLong(h.get("last_rank"));
            if (lr == null || lr <= 0) {
                continue;
            }
            int L = lr.intValue();
            if (collided.contains(L) || slotLastCount.getOrDefault(L, 0) > 1) {
                continue; // 歧义，不锁
            }
            var cand = prevRankToCand.get(L);
            if (cand == null) {
                continue; // 没有候选的上赛季名次等于这个 last_rank
            }
            out.put(intOf(h.get("rank")), new Object[] {cand[1], 0.9,
                    "上赛季「" + nameOf(idx, prev) + "」第 " + L + " 名", cand[0]});
        }
        return out;
    }

    // ───────────────────────── 确定性身份递归 ─────────────────────────

    /** 返回 [名字, 置信度(double), 来源说明]。 */
    private Object[] resolveIdentity(Map<String, RankSliceStore.KeyInfo> idx, String rankKey,
            int partition, int lastRank, int depth) {
        if (depth > 8) {
            return null;
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
            double conf = Math.max(0.4, 0.9 - depth * 0.12);
            String via = "上赛季「" + nameOf(idx, prev) + "」第 " + lastRank + " 名"
                    + (depth > 0 ? "（递归" + (depth + 1) + "层）" : "");
            return new Object[] {String.valueOf(nm), Math.round(conf * 100) / 100.0, via};
        }
        Long deeper = toLong(row.get("last_rank"));
        if (deeper == null || deeper <= 0) {
            return null;
        }
        return resolveIdentity(idx, prev, partition, deeper.intValue(), depth + 1);
    }

    /**
     * 沿 last_rank 在「同分区」逐赛季回溯，收集该槽位的完整实名轨迹（新→旧）。
     * 每跳：到上一季同分区该名次的行，记 {名次, 名字(过去赛季公开，可能 null=那季也隐藏/缺)}，再用该行的 last_rank 继续往上一季挖；
     * <b>出现实名也不停</b>，挖满 {@link #SEASON_DEPTH} 季或取不到行/无 last_rank 为止。结果整段喂 AI 综合判定身份。
     */
    private List<AiPredictor.TrajPoint> buildSlotChain(Map<String, RankSliceStore.KeyInfo> idx,
            String rankKey, int partition, Long lastRank) {
        var traj = new ArrayList<AiPredictor.TrajPoint>();
        String season = prevSeasonOf(idx, rankKey);
        Long r = lastRank;
        var seen = new HashSet<String>();
        int depth = 0;
        while (season != null && r != null && r > 0 && depth < SEASON_DEPTH && seen.add(season)) {
            var list = rankStore.rowAt(season, partition, r.intValue());
            if (list.isEmpty()) {
                break;
            }
            var row = list.get(0);
            Object nm = row.get("char_name");
            String name = (nm != null && !String.valueOf(nm).isBlank()) ? String.valueOf(nm) : null;
            traj.add(new AiPredictor.TrajPoint(r.intValue(), name));
            r = toLong(row.get("last_rank"));
            season = prevSeasonOf(idx, season);
            depth++;
        }
        return traj;
    }

    // ───────────────────────── 工具 ─────────────────────────

    public Map<String, RankSliceStore.KeyInfo> keyIndex() {
        var m = new LinkedHashMap<String, RankSliceStore.KeyInfo>();
        for (var k : store.listKeys()) {
            m.put(k.rankKey(), k);
        }
        return m;
    }

    private static String prevSeasonOf(Map<String, RankSliceStore.KeyInfo> idx, String rankKey) {
        var k = idx.get(rankKey);
        if (k == null || k.prevSeason() == null || k.prevSeason().isBlank()) {
            return null;
        }
        return k.prevSeason().trim();
    }

    private static List<String> seasonChain(Map<String, RankSliceStore.KeyInfo> idx, String rankKey, int max) {
        var chain = new ArrayList<String>();
        var seen = new HashSet<String>();
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

    private static String nameOf(Map<String, RankSliceStore.KeyInfo> idx, String rankKey) {
        var k = idx.get(rankKey);
        return (k == null || k.label() == null || k.label().isBlank()) ? rankKey : k.label();
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
