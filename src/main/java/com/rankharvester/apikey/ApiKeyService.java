package com.rankharvester.apikey;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * API key 服务：内存缓存有效 key（鉴权走内存，<b>不</b>每次查库），后台增删/失效后刷新缓存；
 * 调用统计（最近使用 / 次数）在内存累计，每分钟批量回写库。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyStore store;
    /** key 字符串 → 记录（仅 active 的进缓存；鉴权用）。 */
    private final Map<String, ApiKeyStore.ApiKey> cache = new ConcurrentHashMap<>();
    /** id → [最近使用时刻, 待回写次数]。 */
    private final Map<Long, long[]> usage = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyStore store) {
        this.store = store;
    }

    @PostConstruct
    void load() {
        reloadCache();
    }

    private void reloadCache() {
        var fresh = new ConcurrentHashMap<String, ApiKeyStore.ApiKey>();
        for (ApiKeyStore.ApiKey k : store.all()) {
            if (k.active() && k.key() != null) {
                fresh.put(k.key(), k);
            }
        }
        cache.clear();
        cache.putAll(fresh);
    }

    /** 校验 key：有效返回记录并累计一次调用；无效/停用返回 null。 */
    public ApiKeyStore.ApiKey validate(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        ApiKeyStore.ApiKey k = cache.get(key.trim());
        if (k == null) {
            return null;
        }
        long[] v = usage.computeIfAbsent(k.id(), id -> new long[2]);
        synchronized (v) {
            v[0] = System.currentTimeMillis();
            v[1]++;
        }
        return k;
    }

    public ApiKeyStore.ApiKey create(String note) {
        var k = store.create(note);
        cache.put(k.key(), k);
        return k;
    }

    /** 用户自助：查自己名下的 key（带实时用量）。 */
    public ApiKeyStore.ApiKey findByUser(long userId) {
        return store.findByUser(userId);
    }

    /** 用户自助生成/重置：每用户一个 key，旧的立即失效（删旧建新）。 */
    public ApiKeyStore.ApiKey resetForUser(long userId, String username) {
        store.deleteByUser(userId);
        var k = store.create("用户自助 · " + username, userId);
        reloadCache();
        return k;
    }

    /** 实时调用次数（库值 + 内存待回写增量）。 */
    public long liveCallCount(ApiKeyStore.ApiKey k) {
        long[] u = usage.get(k.id());
        return k.callCount() + (u == null ? 0 : u[1]);
    }

    public void setActive(long id, boolean active) {
        store.setActive(id, active);
        reloadCache();
    }

    public void delete(long id) {
        store.delete(id);
        reloadCache();
    }

    /** 后台列表（含完整 key、备注、状态、创建/最近使用时间、调用次数）；实时反映内存里待回写的次数。 */
    public List<Map<String, Object>> list() {
        var out = new java.util.ArrayList<Map<String, Object>>();
        for (ApiKeyStore.ApiKey k : store.all()) {
            long[] u = usage.get(k.id());
            long calls = k.callCount() + (u == null ? 0 : u[1]);
            long lastUsed = u != null && u[0] > k.lastUsedAt() ? u[0] : k.lastUsedAt();
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", k.id());
            m.put("key", k.key());
            m.put("note", k.note());
            m.put("active", k.active());
            m.put("createdAt", k.createdAt());
            m.put("lastUsedAt", lastUsed);
            m.put("callCount", calls);
            m.put("userId", k.userId());
            out.add(m);
        }
        return out;
    }

    /** 每分钟把内存里的使用统计批量回写库。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    void flushUsage() {
        for (Map.Entry<Long, long[]> e : usage.entrySet()) {
            long[] v = e.getValue();
            long add;
            long lastUsed;
            synchronized (v) {
                add = v[1];
                lastUsed = v[0];
                v[1] = 0;
            }
            if (add > 0) {
                try {
                    store.bumpUsage(e.getKey(), lastUsed, add);
                } catch (RuntimeException ex) {
                    log.debug("回写 api_key 用量失败 id={}: {}", e.getKey(), ex.toString());
                }
            }
        }
    }
}
