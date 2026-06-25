package com.rankharvester.rank.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 全局抓取活动追踪：记录「当前正在抓取哪些榜/分区」，并给出一个智能进度。
 *
 * <p>抓取代码在开始一项抓取时调 {@link #begin}、结束时调 {@link #end}，仅用于「正在刷新」列表 + busy 判定。
 * 进度百分比用<b>剩余待办的高水位</b>口径（见 {@link #snapshot(long)}）：分母=本波峰值待办，分子=峰值−当前剩余，
 * 故 bar 随待办从峰值排空 0→100。<b>刻意不</b>用「累计完成数」做分子——连续抓取下累计数会无限增长、压过剩余量
 * 把 bar 永久顶到 ~100%（即旧实现「分母=已开始项数」的同类毛病）。
 * 前端轮询 {@code /api/public/fetch-activity} 渲染全局进度条 + 正在刷新列表。
 */
@Service
public class FetchActivityService {

    /** 空闲超过此时长后，下一波抓取重置进度基线。 */
    private static final long IDLE_RESET_MS = 8000L;
    /** 待办量噪声地板：剩余 ≤ 此值视为「已追平」，bar 显示 100% 并重设峰值基线（避免稳态小抖动让 bar 乱跳）。 */
    private static final long NOISE_FLOOR = 6L;

    /** 正在抓取的项：id → 描述（rankKey/code + 分区）。 */
    public record Item(String rankKey, Integer partition, boolean slice, long startMs) {}

    private final ConcurrentHashMap<String, Item> active = new ConcurrentHashMap<>();
    /** 本波待办量峰值（高水位）。bar = (peak−当前剩余)/peak。追平/空闲时重设为当前剩余，下一波从 0 重爬。 */
    private long peakRemaining = 0L;
    private volatile long lastActivityMs = 0L;

    /** 开始一项抓取。id 需唯一（同一项 begin/end 配对）。slice=true 表示分区榜（partition 有意义）。 */
    public void begin(String id, String rankKey, Integer partition, boolean slice) {
        lastActivityMs = System.currentTimeMillis();
        active.put(id, new Item(rankKey, partition, slice, now()));
    }

    public void end(String id) {
        if (active.remove(id) != null) {
            lastActivityMs = System.currentTimeMillis();
        }
    }

    /**
     * 新一波抓取开始时调用（如分区榜 30 分钟 {@code fetchAll}）：重设进度峰值基线，使本波 bar 从 0 重新爬，
     * 避免上一波留下的高峰值（{@code peakRemaining} 只增）把新波百分比压在高位起步。
     */
    public synchronized void newWave() {
        peakRemaining = 0L;
        lastActivityMs = System.currentTimeMillis();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * 当前活动快照（在抓项 + 智能进度）。
     *
     * <p>进度口径 = 剩余待办高水位：维护本波峰值 {@code peak}，{@code percent=(peak−remaining)/peak}。
     * 待办排空 → 爬到 100；中途又涌入新待办 → peak 抬高、percent 回落（如实反映）。剩余 ≤ {@link #NOISE_FLOOR}
     * 视为追平：percent=100 且把 peak 重设到当前，确保下一波从 0 重新爬，<b>不</b>受连续运行的累计影响。
     *
     * @param extraRemaining 本波<b>尚未完成</b>的待办量（普通榜：队列 dueCount+lease；分区榜：在途来源数 + 即刻待办）。
     */
    public synchronized Snapshot snapshot(long extraRemaining) {
        var items = new ArrayList<>(active.values());
        boolean busy = !items.isEmpty();
        long remaining = Math.max(0L, extraRemaining);

        // 空闲太久：重置基线（避免显示残留的旧进度）
        if (!busy && System.currentTimeMillis() - lastActivityMs > IDLE_RESET_MS) {
            peakRemaining = 0L;
            return new Snapshot(false, 0, 0, 0, items);
        }
        if (remaining > peakRemaining) {
            peakRemaining = remaining; // 新待办涌入，抬高峰值
        }
        if (remaining <= NOISE_FLOOR) {
            peakRemaining = remaining; // 已追平：重设基线，下一波从 0 重爬
            return new Snapshot(busy, busy ? 100 : 0, (int) peakRemaining, (int) peakRemaining, items);
        }
        long peak = peakRemaining;
        long done = Math.max(0L, peak - remaining);
        int percent = peak > 0 ? (int) Math.min(100, Math.round(done * 100.0 / peak)) : 0;
        return new Snapshot(busy, percent, (int) done, (int) peak, items);
    }

    public record Snapshot(boolean busy, int percent, int done, int total, List<Item> active) {}

    /** 转成前端友好的 Map（名称由调用方/控制器补）。{@code extraRemaining} 见 {@link #snapshot(long)}。 */
    public Map<String, Object> toMap(java.util.function.Function<Item, String> labeler, long extraRemaining) {
        Snapshot s = snapshot(extraRemaining);
        var list = new ArrayList<Map<String, Object>>();
        for (Item it : s.active()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("label", labeler.apply(it));
            m.put("rankKey", it.rankKey());
            m.put("partition", it.partition() == null ? null : it.partition() + 1); // 展示 1-based
            m.put("slice", it.slice());
            list.add(m);
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("busy", s.busy());
        out.put("percent", s.percent());
        out.put("done", s.done());
        out.put("total", s.total());
        out.put("activeCount", list.size());
        out.put("active", list);
        return out;
    }
}
