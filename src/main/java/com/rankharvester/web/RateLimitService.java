package com.rankharvester.web;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的滑动小时窗限流（固定窗口）。key + 小时桶 计数，超额拒绝。
 * Redis 不可用时<strong>放行</strong>（不因限流组件故障阻断主功能）。
 */
@Service
public class RateLimitService {

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 在「每小时」窗口内对 {@code key} 计数并判断是否允许。
     *
     * @return true=允许（未超额）；false=已达上限
     */
    public boolean allowPerHour(String key, int limit) {
        long hourBucket = System.currentTimeMillis() / 3_600_000L;
        String redisKey = "rl:" + key + ":" + hourBucket;
        try {
            Long n = redis.opsForValue().increment(redisKey);
            if (n != null && n == 1L) {
                redis.expire(redisKey, Duration.ofHours(1));
            }
            return n == null || n <= limit;
        } catch (RuntimeException e) {
            return true; // Redis 故障不阻断
        }
    }

    /**
     * 在「每日」窗口（北京时间自然日）内对 {@code key} 计数并判断是否允许。
     *
     * @return true=允许（未超额）；false=已达上限
     */
    public boolean allowPerDay(String key, int limit) {
        long dayBucket = (System.currentTimeMillis() + 8 * 3_600_000L) / 86_400_000L;
        String redisKey = "rl:d:" + key + ":" + dayBucket;
        try {
            Long n = redis.opsForValue().increment(redisKey);
            if (n != null && n == 1L) {
                redis.expire(redisKey, Duration.ofHours(25));
            }
            return n == null || n <= limit;
        } catch (RuntimeException e) {
            return true; // Redis 故障不阻断
        }
    }
}
