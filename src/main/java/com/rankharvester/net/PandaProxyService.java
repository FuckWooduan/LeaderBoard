package com.rankharvester.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 熊猫代理（动态住宅 IP）出口提供器 —— 仅用于「排行榜抓取」连接。
 *
 * <p><b>固定池复用 + 批量缓冲</b>设计，适配「不限量套餐」：单日不限、但 <b>API 仅 10 秒一次</b>、单次最多 5×N 个。
 * 故每次 API 调用<b>批量</b>拉一批（{@code pullCount} 个）进缓冲；维持 {@code poolSize} 个 IP 槽供所有抓榜连接
 * <b>轮询复用</b>，某槽 IP 过期（{@code ipTtlMs}，住宅 IP 2-10 分钟、取 4 分钟余量）时从缓冲取一个换上（不再每槽单独打 API）。
 *
 * <p>绝不「每条连接拉一个新 IP」——分区抓取上万来源会瞬间打爆 API/额度（曾 22 分钟烧光 1 万）。
 * 反例已用本设计根治：消耗与连接数<b>无关</b>，只跟池大小×刷新频率有关。
 *
 * <p>{@code dailyBudget<=0} 表示不限（不限量套餐）；>0 时为每日硬上限（按北京午夜重置，旧的限量套餐用）。
 * 实测对本机源 IP 走<strong>免认证</strong> SOCKS5，故 {@link ProxyEndpoint} 不带账号密码。
 * 玩家查询的常热长连接<strong>不</strong>走这里（走 sing-box）。
 */
public final class PandaProxyService {

    private static final Logger log = LoggerFactory.getLogger(PandaProxyService.class);
    private static final long CST_OFFSET_MS = 8 * 3600_000L;
    /** 拉取失败（额度用完/限流）后的冷却，避免空刷 API。 */
    private static final long PULL_FAIL_COOLDOWN_MS = 15_000L;

    private final boolean enabled;
    private final String apiUrl;
    private final int poolSize;
    private final int pullCount;
    private final long ipTtlMs;
    private final long minPullIntervalMs;
    private final int dailyBudget;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    private record Buffered(ProxyEndpoint ep, long pulledAt) {}

    /** IP 槽（被连接轮询复用）+ 各槽拉取时间。 */
    private final ProxyEndpoint[] slots;
    private final long[] slotPulledAt;
    private final AtomicInteger rr = new AtomicInteger();
    /** 批量拉取的备用新鲜 IP 缓冲（供刷新过期槽，不再每槽打 API）。 */
    private final Deque<Buffered> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    /** 登录「三振」被弃用的坏 IP（host:port → 解禁时刻）；acquire/缓冲都跳过，到点自动解禁。 */
    private final Map<String, Long> badUntil = new HashMap<>();
    private static final long BLACKLIST_MS = 5 * 60_000L;
    /** 各 IP 连续登录失败计数（host:port → 次数）；累计 {@link #IP_STRIKES} 次即拉黑，成功清零。 */
    private final Map<String, Integer> failStrikes = new HashMap<>();
    private static final int IP_STRIKES = 5; // 同一 IP 连续失败几次才拉黑（调高=更宽容，少拉黑少空转）

    private long lastPullAt = 0L;
    private long pullCooldownUntil = 0L;
    private long dayEpoch = -1L;
    private int dailyPulled = 0;

    public PandaProxyService(GameProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.apiUrl = props.getPandaApiUrl() == null ? "" : props.getPandaApiUrl().trim();
        this.poolSize = Math.max(1, props.getPandaPoolSize());
        this.pullCount = Math.max(1, props.getPandaPullCount());
        this.ipTtlMs = Math.max(30_000L, props.getPandaIpTtlMs());
        this.minPullIntervalMs = Math.max(1000L, props.getPandaMinPullIntervalMs());
        this.dailyBudget = props.getPandaDailyBudget();
        this.slots = new ProxyEndpoint[poolSize];
        this.slotPulledAt = new long[poolSize];
        this.enabled = props.isPandaEnabled() && !apiUrl.isBlank();
        if (props.isPandaEnabled() && apiUrl.isBlank()) {
            log.warn("熊猫代理已启用但未配置提取 API（rankharvester.game.panda-api-url 为空），抓榜将回退 sing-box/直连");
        } else if (enabled) {
            log.info("抓榜代理：熊猫固定池复用（{} 个 IP 轮询，每次批量拉 {} 个，IP TTL {}s，提取间隔≥{}ms，每日上限 {}）",
                    poolSize, pullCount, ipTtlMs / 1000, minPullIntervalMs, dailyBudget <= 0 ? "不限" : dailyBudget);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 取一个抓榜出口 IP（固定池轮询复用，免认证 SOCKS5）。未启用/无可用返回 null（回退 sing-box）。 */
    public ProxyEndpoint acquire() {
        if (!enabled) {
            return null;
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            // 轮询找一个「未拉黑」的槽复用；只在槽 IP 真正 TTL 过期(或为空)时才换新 IP。
            // 关键：拉黑只是「跳过该 IP」，不再「一拉黑就强拉新 IP」——避免代理普遍坏时拉黑↔拉新的空转。
            for (int tries = 0; tries < poolSize; tries++) {
                int idx = Math.floorMod(rr.getAndIncrement(), poolSize);
                boolean ttlExpired = slots[idx] == null || (now - slotPulledAt[idx] >= ipTtlMs);
                if (ttlExpired) {
                    ProxyEndpoint fresh = pollBuffer(now);
                    if (fresh == null && canPull(now)) {
                        pullBatch(now);
                        fresh = pollBuffer(now);
                    }
                    if (fresh != null) {
                        slots[idx] = fresh;
                        slotPulledAt[idx] = now;
                    }
                }
                if (slots[idx] != null && !isBad(slots[idx], now)) {
                    return slots[idx]; // 找到可用 IP
                }
            }
            // 一圈全是拉黑/空：兜底返回当前槽(带病比没有强)，不再额外狂拉新 IP。
            return slots[Math.floorMod(rr.get(), poolSize)];
        }
    }

    public ProxyEndpoint refresh() {
        return acquire();
    }

    /** 从缓冲取一个仍新鲜的 IP（丢弃过期的）。 */
    private ProxyEndpoint pollBuffer(long now) {
        Buffered b;
        while ((b = buffer.pollFirst()) != null) {
            if (now - b.pulledAt() < ipTtlMs && !isBad(b.ep(), now)) {
                return b.ep();
            }
        }
        return null;
    }

    /** host:port 键。 */
    private static String key(ProxyEndpoint ep) {
        return ep.host() + ":" + ep.port();
    }

    /** 该 IP 是否在「三振弃用」黑名单内（到点自动解禁）。调用方需持有 {@link #lock}。 */
    private boolean isBad(ProxyEndpoint ep, long now) {
        if (ep == null) {
            return false;
        }
        Long until = badUntil.get(key(ep));
        if (until == null) {
            return false;
        }
        if (now >= until) {
            badUntil.remove(key(ep));
            return false;
        }
        return true;
    }

    /** 拉黑某 IP 并清出占用它的槽（调用方需持有 {@link #lock}）。 */
    private void blacklistLocked(ProxyEndpoint ep, long now) {
        badUntil.put(key(ep), now + BLACKLIST_MS);
        failStrikes.remove(key(ep));
        // 不再 null 槽：acquire 会按 isBad 跳过它，到 TTL 自然换新——避免「一拉黑就强拉新 IP」的空转。
    }

    /** 立即弃用某 IP（主榜登录「同 IP 连试 3 号都失败」直接换 IP 时调用）。 */
    public void reportBadIp(ProxyEndpoint ep) {
        if (ep == null) {
            return;
        }
        synchronized (lock) {
            blacklistLocked(ep, System.currentTimeMillis());
        }
        log.info("熊猫 IP {} 弃用 {}s", key(ep), BLACKLIST_MS / 1000);
    }

    /** 登录失败累计：同一 IP 累计 {@link #IP_STRIKES} 次失败 → 拉黑（分区发现/抓取按 IP 跨任务累计，成功请调 reportLoginSuccess 清零）。 */
    public void reportLoginFailure(ProxyEndpoint ep) {
        if (ep == null) {
            return;
        }
        boolean blacklisted = false;
        synchronized (lock) {
            int n = failStrikes.merge(key(ep), 1, Integer::sum);
            if (n >= IP_STRIKES) {
                blacklistLocked(ep, System.currentTimeMillis());
                blacklisted = true;
            }
        }
        if (blacklisted) {
            log.info("熊猫 IP {} 连续 {} 次登录失败，弃用 {}s", key(ep), IP_STRIKES, BLACKLIST_MS / 1000);
        }
    }

    /** 登录成功：清零该 IP 的失败计数。 */
    public void reportLoginSuccess(ProxyEndpoint ep) {
        if (ep == null) {
            return;
        }
        synchronized (lock) {
            failStrikes.remove(key(ep));
        }
    }

    /** 取一个与 {@code avoid} 不同的出口 IP（先拉黑 avoid 再 acquire）。池里没有别的新鲜 IP 时返回 null。 */
    public ProxyEndpoint acquireDifferent(ProxyEndpoint avoid) {
        reportBadIp(avoid);
        ProxyEndpoint ep = acquire();
        if (ep != null && avoid != null && key(ep).equals(key(avoid))) {
            return null; // 池里没有别的 IP 了
        }
        return ep;
    }

    /** 限速 + 失败冷却 + 每日预算（<=0 不限）。 */
    private boolean canPull(long now) {
        if (now - lastPullAt < minPullIntervalMs || now < pullCooldownUntil) {
            return false;
        }
        if (dailyBudget > 0) {
            long today = (now + CST_OFFSET_MS) / 86_400_000L;
            if (today != dayEpoch) {
                dayEpoch = today;
                dailyPulled = 0;
            }
            return dailyPulled < dailyBudget;
        }
        return true;
    }

    /** 批量拉一批 IP 进缓冲（count={@link #pullCount}）；成功计入当日数、清冷却，失败设冷却。 */
    private void pullBatch(long now) {
        lastPullAt = now;
        pullCooldownUntil = now + PULL_FAIL_COOLDOWN_MS;
        String url = withCount(apiUrl, pullCount);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("熊猫提取 API 非 2xx：status={} body={}", resp.statusCode(), trim(resp.body()));
                return;
            }
            JsonNode root = mapper.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.warn("熊猫提取失败：code={} msg={}（冷却 {}s）", code, root.path("msg").asString(""),
                        PULL_FAIL_COOLDOWN_MS / 1000);
                return;
            }
            JsonNode obj = root.path("obj");
            if (!obj.isArray() || obj.isEmpty()) {
                log.warn("熊猫提取无 IP：{}", trim(resp.body()));
                return;
            }
            int added = 0;
            for (JsonNode n : obj) {
                String ip = n.path("ip").asString("");
                int port = n.path("port").asInt(0);
                if (!ip.isBlank() && port > 0) {
                    buffer.offerLast(new Buffered(new ProxyEndpoint(ip, port, null, null), now));
                    added++;
                }
            }
            if (added > 0) {
                pullCooldownUntil = 0L;
                dailyPulled += added;
                log.info("熊猫批量换 IP {} 个入缓冲（现 {} 个；当日已用 {}{}）",
                        added, buffer.size(), dailyPulled, dailyBudget > 0 ? "/" + dailyBudget : "（不限）");
            }
        } catch (Exception e) {
            log.warn("熊猫提取 API 调用失败：{}", e.toString());
        }
    }

    private static String withCount(String url, int count) {
        if (url.matches(".*[?&]count=\\d+.*")) {
            return url.replaceAll("([?&]count=)\\d+", "$1" + count);
        }
        return url + (url.contains("?") ? "&" : "?") + "count=" + count;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
