package com.rankharvester.web;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 网站访问统计（按 IP 去重）。
 *
 * <ul>
 *   <li>当天独立访客：Redis SET {@code visit:day:yyyyMMdd}（SADD ip，2 天过期），SCARD 即当天去重人数。</li>
 *   <li>累计独立访客：Redis HyperLogLog {@code visit:hll:all}（PFADD ip），PFCOUNT 即累计去重人数（近似，省内存）。</li>
 * </ul>
 * Redis 故障时返回 0，不影响页面。
 */
@Service
public class VisitStatsService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate redis;

    public VisitStatsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 当天 / 累计 去重访客数。 */
    public record Stats(long today, long total) {}

    private static final String API_CALLS_KEY = "internal:api:calls";
    private static final String API_TOTAL_KEY = "api:calls:total";
    private static final String API_TODAY_PREFIX = "api:calls:day:";

    /** 内部 API 被调用一次（持久累计，跨重启不清零）。Redis 故障忽略。 */
    public void incrInternalApi() {
        incr(API_CALLS_KEY);
    }

    /** 任意 API 被调用一次（总计 + 当日；前端轮询类已在 filter 排除）。 */
    public void incrApiTotal() {
        incr(API_TOTAL_KEY);
        incrDaily(todayApiKey());
    }

    private void incr(String key) {
        try {
            redis.opsForValue().increment(key);
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    /** 当日计数：首次创建时设 2 天过期，按日期键天然区分「今日」。 */
    private void incrDaily(String key) {
        try {
            Long v = redis.opsForValue().increment(key);
            if (v != null && v == 1L) {
                redis.expire(key, Duration.ofDays(2));
            }
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    private String todayApiKey() {
        return API_TODAY_PREFIX + LocalDate.now(CN).toString().replace("-", "");
    }

    /** 内部 API 累计调用次数。 */
    public long internalApiCalls() {
        return read(API_CALLS_KEY);
    }

    /** 全部 API 累计调用次数。 */
    public long totalApiCalls() {
        return read(API_TOTAL_KEY);
    }

    /** 今日 API 调用次数（按 Asia/Shanghai 自然日，每日归零）。 */
    public long todayApiCalls() {
        return read(todayApiKey());
    }

    private long read(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 记录一次访问（按 IP 去重）并返回最新统计。 */
    public Stats recordAndGet(String ip) {
        String day = LocalDate.now(CN).toString().replace("-", "");
        String dayKey = "visit:day:" + day;
        String allKey = "visit:hll:all";
        try {
            if (ip != null && !ip.isBlank()) {
                redis.opsForSet().add(dayKey, ip);
                redis.expire(dayKey, Duration.ofDays(2));
                redis.opsForHyperLogLog().add(allKey, ip);
            }
            Long today = redis.opsForSet().size(dayKey);
            Long total = redis.opsForHyperLogLog().size(allKey);
            return new Stats(today == null ? 0 : today, total == null ? 0 : total);
        } catch (RuntimeException e) {
            return new Stats(0, 0);
        }
    }
}
