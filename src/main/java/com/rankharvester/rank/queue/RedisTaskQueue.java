package com.rankharvester.rank.queue;

import com.rankharvester.rank.model.FetchTask;
import com.rankharvester.rank.model.Lane;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 持久化任务队列（AOF 落盘）。
 *
 * <p>结构：
 * <ul>
 *   <li>每 lane 一个 ZSET {@code rh:q:{LANE}}，member=taskId，score=dueAt（≤now 即可领取）。</li>
 *   <li>租约 ZSET {@code rh:lease}，member=taskId，score=leaseUntil；超时由 repair 回收。</li>
 *   <li>任务哈希 {@code rh:task:{taskId}}：code / lane / dueAt / attempts。</li>
 * </ul>
 * 领取用 Lua 脚本保证「按 score 取 due 任务 → 从 lane 移除 → 写入租约」原子完成，避免重复领取。
 */
@Component
public class RedisTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskQueue.class);

    static final String LANE_KEY_PREFIX = "rh:q:";
    static final String LEASE_KEY = "rh:lease";
    static final String TASK_KEY_PREFIX = "rh:task:";

    /** 原子领取：取 score ≤ cutoff 的前 N 个 member，移出 lane、写入租约。返回 member 列表。 */
    private static final DefaultRedisScript<List> POP_DUE = new DefaultRedisScript<>(
            """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[3]))
            for _, m in ipairs(due) do
              redis.call('ZREM', KEYS[1], m)
              redis.call('ZADD', KEYS[2], ARGV[2], m)
            end
            return due
            """,
            List.class);

    /** 原子回收：取租约 score ≤ now 的前 N 个 member 并移出租约。返回 member 列表（lane 由 Java 端按哈希重排）。 */
    private static final DefaultRedisScript<List> POP_EXPIRED = new DefaultRedisScript<>(
            """
            local exp = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            for _, m in ipairs(exp) do
              redis.call('ZREM', KEYS[1], m)
            end
            return exp
            """,
            List.class);

    private final StringRedisTemplate redis;

    public RedisTaskQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String laneKey(Lane lane) {
        return LANE_KEY_PREFIX + lane.name();
    }

    static String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    /** 入队（幂等：同 taskId 已在 lane 或租约中则跳过）。 */
    public void enqueue(String taskId, String leaderboardCode, Lane lane, long dueAtMs) {
        Double inLane = redis.opsForZSet().score(laneKey(lane), taskId);
        Double inLease = redis.opsForZSet().score(LEASE_KEY, taskId);
        if (inLane != null || inLease != null) {
            return; // 已存在，幂等跳过
        }
        var h = redis.opsForHash();
        h.put(taskKey(taskId), "code", leaderboardCode);
        h.put(taskKey(taskId), "lane", lane.name());
        h.put(taskKey(taskId), "dueAt", Long.toString(dueAtMs));
        h.putIfAbsent(taskKey(taskId), "attempts", "0");
        redis.opsForZSet().add(laneKey(lane), taskId, dueAtMs);
    }

    /** 领取某 lane 中 score ≤ cutoff 的任务（cutoff 用于饥饿/常规两种通道）。 */
    @SuppressWarnings("unchecked")
    public List<FetchTask> claim(Lane lane, long cutoffMs, int maxCount, long leaseUntilMs) {
        if (maxCount <= 0) {
            return List.of();
        }
        List<String> members = redis.execute(
                POP_DUE,
                List.of(laneKey(lane), LEASE_KEY),
                Long.toString(cutoffMs),
                Long.toString(leaseUntilMs),
                Integer.toString(maxCount));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<FetchTask>(members.size());
        for (String taskId : members) {
            out.add(hydrate(taskId, lane));
        }
        return out;
    }

    private FetchTask hydrate(String taskId, Lane lane) {
        Map<Object, Object> h = redis.opsForHash().entries(taskKey(taskId));
        String code = (String) h.getOrDefault("code", "");
        long dueAt = parseLong(h.get("dueAt"));
        int attempts = (int) parseLong(h.get("attempts"));
        return new FetchTask(taskId, code, lane, dueAt, dueAt, attempts);
    }

    /** 成功完成：移出租约并删除任务哈希。 */
    public void ack(String taskId) {
        redis.opsForZSet().remove(LEASE_KEY, taskId);
        redis.delete(taskKey(taskId));
    }

    /** 失败：移出租约，按 RETRY lane 以退避时间重新入队（attempts+1）。 */
    public void fail(String taskId, String leaderboardCode, long retryAtMs) {
        redis.opsForZSet().remove(LEASE_KEY, taskId);
        redis.opsForHash().increment(taskKey(taskId), "attempts", 1);
        redis.opsForHash().put(taskKey(taskId), "lane", Lane.RETRY.name());
        redis.opsForHash().put(taskKey(taskId), "dueAt", Long.toString(retryAtMs));
        redis.opsForZSet().add(RedisTaskQueue.laneKey(Lane.RETRY), taskId, retryAtMs);
    }

    /** 回收过期租约：重新入回原 lane（score=now），attempts+1。返回回收条数。 */
    @SuppressWarnings("unchecked")
    public int repairExpiredLeases(long nowMs, int maxCount) {
        List<String> expired =
                redis.execute(POP_EXPIRED, List.of(LEASE_KEY), Long.toString(nowMs), Integer.toString(maxCount));
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        for (String taskId : expired) {
            var h = redis.opsForHash();
            String laneName = (String) h.get(taskKey(taskId), "lane");
            Lane lane = laneName != null ? safeLane(laneName) : Lane.RETRY;
            h.increment(taskKey(taskId), "attempts", 1);
            h.put(taskKey(taskId), "dueAt", Long.toString(nowMs));
            redis.opsForZSet().add(laneKey(lane), taskId, nowMs);
        }
        log.warn("回收过期租约 {} 条", expired.size());
        return expired.size();
    }

    /** 某 lane 当前积压（含未到期）。 */
    public long backlog(Lane lane) {
        Long n = redis.opsForZSet().zCard(laneKey(lane));
        return n == null ? 0 : n;
    }

    /** 某 lane 已到期（score ≤ now）的可领取数量。 */
    public long dueCount(Lane lane, long nowMs) {
        Long n = redis.opsForZSet().count(laneKey(lane), Double.NEGATIVE_INFINITY, nowMs);
        return n == null ? 0 : n;
    }

    public long leaseSize() {
        Long n = redis.opsForZSet().zCard(LEASE_KEY);
        return n == null ? 0 : n;
    }

    public Map<Lane, Long> backlogByLane() {
        var map = new java.util.EnumMap<Lane, Long>(Lane.class);
        for (Lane lane : Lane.values()) {
            map.put(lane, backlog(lane));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Lane safeLane(String name) {
        try {
            return Lane.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Lane.RETRY;
        }
    }

    private static long parseLong(Object v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
