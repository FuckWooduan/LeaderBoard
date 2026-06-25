package com.rankharvester.web;

import com.rankharvester.activity.ActivityBroadcastService;
import com.rankharvester.activity.ActivityEmailService;
import com.rankharvester.activity.ActivityFetcher;
import com.rankharvester.activity.ActivityImageRenderService;
import com.rankharvester.activity.ActivityRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开「活动通知」接口（前端活动详情页用）：
 *
 * <ul>
 *   <li>{@code GET /api/public/activities} —— 全部活动详情 + 最近抓取时间 + 下次检测时间 + 订阅人数。</li>
 *   <li>{@code GET /api/public/activity/feed?since=&limit=} —— <b>机器人轮询「新活动」游标 feed</b>：
 *       封装抓取+判新+图片链接，接入方定时轮询即可发现新活动并自行推送（首次基线不推、之后传 since 取增量）。</li>
 *   <li>{@code GET /api/public/activity/image?activityId=N} —— 活动长图(report.png)，缺失则<b>按需渲染</b>并缓存。</li>
 *   <li>{@code POST /api/public/activity/subscribe} —— 订阅最新活动通知（过 Turnstile），成功发确认邮件。</li>
 *   <li>{@code POST /api/public/activity/unsubscribe} —— 退订。</li>
 * </ul>
 */
@RestController
public class ActivityPublicController {

    private static final Logger log = LoggerFactory.getLogger(ActivityPublicController.class);
    private static final java.time.Duration STATIC_ACTIVITY_TTL = java.time.Duration.ofDays(365);

    private final ActivityBroadcastService broadcast;
    private final ActivityImageRenderService render;
    private final ActivityEmailService email;
    private final CaptchaService captcha;
    /** 站点对外根地址（feed 给机器人返回活动长图/页面的绝对链接用）。 */
    private final String baseUrl;

    /** 已排队/在渲的 activityId（去重，避免同一活动被重复渲染、避免任务堆积）。 */
    private final java.util.Set<Long> renderQueued = ConcurrentHashMap.newKeySet();
    /** 后台渲染线程池：固定 3 线程（FFDEC 渲染吃 CPU），渲染<b>绝不</b>占用 Tomcat 请求线程，避免高并发拖垮服务。 */
    private final java.util.concurrent.ExecutorService renderExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "activity-render");
                t.setDaemon(true);
                return t;
            });

    private static final int MAX_PAGE_SIZE = 60;

    public ActivityPublicController(ActivityBroadcastService broadcast, ActivityImageRenderService render,
            ActivityEmailService email, CaptchaService captcha,
            @org.springframework.beans.factory.annotation.Value("${rankharvester.public.base-url:https://example.com}")
            String baseUrl) {
        this.broadcast = broadcast;
        this.render = render;
        this.email = email;
        this.captcha = captcha;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://example.com" : baseUrl.replaceAll("/+$", "");
    }

    /**
     * 活动列表（分页，<b>新→旧</b>）。活动总数很大（数千），全量渲染长图会拖垮渲染器，故分页 + 长图按需懒渲染。
     *
     * @param page 页码（0 基）
     * @param size 每页条数（默认 24，上限 {@value #MAX_PAGE_SIZE}）
     */
    @GetMapping("/api/public/activities")
    public Map<String, Object> activities(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false, defaultValue = "") String q) {
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int pageNo = Math.max(0, page);
        String kw = q == null ? "" : q.trim().toLowerCase();
        var out = new LinkedHashMap<String, Object>();
        out.put("lastFetchAt", broadcast.lastFetchAt());
        out.put("nextDetectAt", broadcast.nextDetectAt());
        out.put("subscriberCount", email.count());
        out.put("page", pageNo);
        out.put("size", pageSize);
        var list = new ArrayList<Map<String, Object>>();
        try {
            ActivityFetcher.FetchBundle bundle = broadcast.getOrFetchBundle();
            List<ActivityRow> rows = bundle.rows();
            // 新→旧 + 按名称(标题/描述)搜索过滤
            var matched = new ArrayList<ActivityRow>();
            for (int i = rows.size() - 1; i >= 0; i--) {
                ActivityRow a = rows.get(i);
                if (kw.isEmpty() || matchesKeyword(a, kw)) {
                    matched.add(a);
                }
            }
            int total = matched.size();
            out.put("total", total);
            int from = pageNo * pageSize;
            for (int i = from, n = 0; i < total && n < pageSize; i++, n++) {
                ActivityRow a = matched.get(i);
                var m = new LinkedHashMap<String, Object>();
                m.put("activityId", a.activityId());
                m.put("title", a.title());
                m.put("description", a.description());
                m.put("startTime", a.startTime());
                m.put("endTime", a.endTime());
                m.put("hasImage", a.url() != null && !a.url().isBlank()); // 有 SWF 才可能渲长图
                m.put("firstSeenAt", broadcast.firstSeenAt(a.activityId())); // 首次抓到时刻（0=存量无记录）
                list.add(m);
            }
            out.put("ok", true);
        } catch (RuntimeException e) {
            log.warn("[活动页] 取活动列表失败: {}", e.toString());
            out.put("ok", false);
            out.put("total", 0);
            out.put("message", "活动抓取暂时失败，请稍后刷新");
        }
        out.put("count", list.size());
        out.put("activities", list);
        return out;
    }

    /**
     * 机器人「新活动」轮询 feed（游标式，无状态、公开）。把<b>抓取→判新→图片链接</b>全封装好，
     * 接入方只需定时 GET 此端点即可发现新活动并自行推送。
     *
     * <p><b>用法</b>：
     * <ol>
     *   <li>首次<b>不带</b> {@code since} 调用 → 返回最新若干条 + {@code isBaseline=true} + {@code nextSince}（当前最大 activityId）。
     *       接入方<b>存下 {@code nextSince} 但不要推送</b>（避免上线即把存量活动全刷一遍，与服务端邮件冷启动一致）。</li>
     *   <li>之后每隔几分钟带 {@code ?since=上次的 nextSince} 调用 → 返回 activityId 大于 since 的新活动（<b>按 id 升序</b>）。
     *       接入方推送这些活动，并把 {@code since} 更新为本次 {@code nextSince}。</li>
     *   <li>若 {@code hasMore=true} 表示新活动超过本页上限，应尽快再次轮询取剩余。</li>
     * </ol>
     *
     * <p>每条活动含长图绝对链接 {@code imageUrl}（服务端已<b>预触发渲染</b>，接入方稍后下载即可拿到）、
     * SWF 链接 {@code swfUrl}、活动页 {@code pageUrl}、标题/描述(原文+纯文本)/起止时间。
     *
     * @param since 上次返回的 {@code nextSince}（机器人记录的最大已见 activityId）；不传=首次基线。
     * @param limit 单次最多返回条数（默认 20，上限 {@value #MAX_PAGE_SIZE}）。
     */
    @GetMapping("/api/public/activity/feed")
    public Map<String, Object> feed(
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "20") int limit) {
        int cap = Math.min(MAX_PAGE_SIZE, Math.max(1, limit));
        boolean baseline = (since == null);
        var out = new LinkedHashMap<String, Object>();
        out.put("serverTime", System.currentTimeMillis());
        out.put("lastFetchAt", broadcast.lastFetchAt());
        out.put("nextDetectAt", broadcast.nextDetectAt());
        out.put("isBaseline", baseline);
        try {
            ActivityFetcher.FetchBundle bundle = broadcast.getOrFetchBundle();
            List<ActivityRow> rows = bundle.rows();
            long maxId = 0L;
            for (ActivityRow a : rows) {
                maxId = Math.max(maxId, a.activityId());
            }

            List<ActivityRow> selected = new ArrayList<>();
            if (baseline) {
                // 基线：最新 cap 条（新→旧）做预览，接入方仅存 nextSince、不推送
                for (int i = rows.size() - 1; i >= 0 && selected.size() < cap; i--) {
                    selected.add(rows.get(i));
                }
                out.put("nextSince", maxId);
                out.put("hasMore", false);
            } else {
                // 增量：id > since，按 id 升序（接入方推完把 since 进到本页最大 id，hasMore 时继续轮询取剩余）
                long sinceId = since;
                var fresh = new ArrayList<ActivityRow>();
                for (ActivityRow a : rows) {
                    if (a.activityId() > sinceId) {
                        fresh.add(a);
                    }
                }
                fresh.sort(java.util.Comparator.comparingLong(ActivityRow::activityId));
                boolean hasMore = fresh.size() > cap;
                for (int i = 0; i < fresh.size() && i < cap; i++) {
                    selected.add(fresh.get(i));
                }
                long nextSince = selected.isEmpty() ? sinceId : selected.get(selected.size() - 1).activityId();
                out.put("nextSince", nextSince);
                out.put("hasMore", hasMore);
            }

            var list = new ArrayList<Map<String, Object>>();
            for (ActivityRow a : selected) {
                boolean hasImage = a.url() != null && !a.url().isBlank();
                if (hasImage) {
                    submitRender(a.activityId()); // 预触发长图渲染，机器人稍后下载即得
                }
                var m = new LinkedHashMap<String, Object>();
                m.put("activityId", a.activityId());
                m.put("title", a.title());
                m.put("description", a.description());
                m.put("descriptionText", stripHtml(a.description()));
                m.put("startTime", a.startTime());
                m.put("endTime", a.endTime());
                m.put("hasImage", hasImage);
                m.put("imageUrl", hasImage ? baseUrl + "/api/public/activity/" + a.activityId() + ".png" : null);
                m.put("swfUrl", hasImage ? baseUrl + "/api/public/activity/" + a.activityId() + ".swf" : null);
                m.put("pageUrl", baseUrl + "/activity");
                list.add(m);
            }
            out.put("count", list.size());
            out.put("activities", list);
            out.put("ok", true);
        } catch (RuntimeException e) {
            log.warn("[活动 feed] 取活动失败: {}", e.toString());
            out.put("ok", false);
            out.put("count", 0);
            out.put("activities", List.of());
            out.put("message", "活动抓取暂时失败，请稍后重试");
        }
        return out;
    }

    @GetMapping(value = "/api/public/activity/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam long activityId) {
        return servePng(activityId);
    }

    /**
     * 长图·缓存友好地址（路径以 {@code .png} 结尾 → Cloudflare 默认按扩展名边缘缓存）。
     * 响应带长期 public 缓存头；活动静态资源生成后不变，可充分利用 CDN 边缘缓存。
     */
    @GetMapping(value = "/api/public/activity/{activityId}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> imageByExt(@org.springframework.web.bind.annotation.PathVariable long activityId) {
        return servePng(activityId);
    }

    /**
     * 活动 SWF（解密后原件，路径以 {@code .swf} 结尾 → Cloudflare 默认边缘缓存）。
     * 前端用 Ruffle 在线播放（含动画与交互）。缺失则后台按需下载解密，前端自动重试。
     */
    @GetMapping(value = "/api/public/activity/{activityId}.swf")
    public ResponseEntity<byte[]> swf(@org.springframework.web.bind.annotation.PathVariable long activityId) {
        Path p = render.swfPath(activityId);
        if (Files.exists(p)) {
            try {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/x-shockwave-flash"))
                        .cacheControl(org.springframework.http.CacheControl
                                .maxAge(STATIC_ACTIVITY_TTL).cachePublic())
                        .body(Files.readAllBytes(p));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }
        submitSwfFetch(activityId);
        return ResponseEntity.notFound()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .build();
    }

    /**
     * 活动 SWF 的 hover 热区与精准 tooltip（静态推断，与长图同源）。
     * 前端在 Ruffle 播放器上叠加此数据自绘 tooltip，绕开 Ruffle 对 AS 的 tooltip 模拟误差。
     * 未生成时丢后台计算并返回 404(不缓存)，前端自动重试。
     */
    @GetMapping(value = "/api/public/activity/{activityId}/interactions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> interactions(@org.springframework.web.bind.annotation.PathVariable long activityId) {
        Path p = render.interactionsJsonPath(activityId);
        if (Files.exists(p)) {
            try {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .cacheControl(org.springframework.http.CacheControl
                                .maxAge(STATIC_ACTIVITY_TTL).cachePublic())
                        .body(Files.readAllBytes(p));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }
        if (!Files.exists(render.swfPath(activityId))) {
            submitSwfFetch(activityId); // SWF 都还没有 → 先下 SWF；下一轮重试再触发推断
        } else {
            submitInteractions(activityId);
        }
        return ResponseEntity.notFound()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .build();
    }

    /** hover 目标 sprite 渲染出的 tooltip 图（interactions 推断时已落盘；路径带 .png → CF 边缘缓存）。 */
    @GetMapping(value = "/api/public/activity/{activityId}/tip/{spriteId}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> tipPng(
            @org.springframework.web.bind.annotation.PathVariable long activityId,
            @org.springframework.web.bind.annotation.PathVariable int spriteId) {
        Path p = render.tipPngPath(activityId, spriteId);
        if (!Files.exists(p)) {
            return ResponseEntity.notFound()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .build();
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(org.springframework.http.CacheControl
                            .maxAge(STATIC_ACTIVITY_TTL).cachePublic())
                    .body(Files.readAllBytes(p));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 把某活动交互推断丢到后台线程池（与长图共用线程池与去重集合，key 加大偏移避免冲突）。 */
    private void submitInteractions(long activityId) {
        long key = 0x2000_0000_0000_0000L + activityId;
        if (!renderQueued.add(key)) {
            return;
        }
        try {
            renderExecutor.submit(() -> {
                try {
                    if (Files.exists(render.interactionsJsonPath(activityId))) {
                        return;
                    }
                    render.renderInteractionsJson(activityId);
                } finally {
                    renderQueued.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            renderQueued.remove(key);
        }
    }

    private ResponseEntity<byte[]> servePng(long activityId) {
        Path p = render.reportPngPath(activityId);
        if (Files.exists(p)) {
            try {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(org.springframework.http.CacheControl
                                .maxAge(STATIC_ACTIVITY_TTL).cachePublic())
                        .body(Files.readAllBytes(p));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }
        // 未缓存：丢后台线程池渲染（渲染绝不占请求线程），立刻返回 404(不缓存)。前端 img onerror 自动重试，渲染好即显示。
        submitRender(activityId);
        return ResponseEntity.notFound()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .build();
    }

    /** 把某活动 SWF 下载丢到后台线程池（与长图共用线程池与去重集合，key 取负避免冲突）。 */
    private void submitSwfFetch(long activityId) {
        long key = -activityId;
        if (!renderQueued.add(key)) {
            return;
        }
        try {
            renderExecutor.submit(() -> {
                try {
                    if (Files.exists(render.swfPath(activityId))) {
                        return;
                    }
                    ActivityFetcher.FetchBundle bundle = broadcast.getOrFetchBundle();
                    ActivityRow row = bundle.rows().stream()
                            .filter(r -> r.activityId() == activityId).findFirst().orElse(null);
                    if (row != null) {
                        Path p = render.fetchSwf(row, bundle.resConfigXml(), bundle.resurlBack());
                        if (p != null && Files.exists(p) && !Files.exists(render.interactionsJsonPath(activityId))) {
                            submitInteractions(activityId);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("[活动页] 后台获取 SWF 失败 activityId={}: {}", activityId, e.toString());
                } finally {
                    renderQueued.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            renderQueued.remove(key);
        }
    }

    /** 把某活动长图渲染丢到后台线程池（去重：同一活动只排一次）。 */
    private void submitRender(long activityId) {
        if (!renderQueued.add(activityId)) {
            return; // 已在排队/渲染中
        }
        try {
            renderExecutor.submit(() -> {
                try {
                    if (Files.exists(render.reportPngPath(activityId))) {
                        return; // 排队期间已被渲好
                    }
                    ActivityFetcher.FetchBundle bundle = broadcast.getOrFetchBundle();
                    ActivityRow row = bundle.rows().stream()
                            .filter(r -> r.activityId() == activityId).findFirst().orElse(null);
                    if (row != null) {
                        render.renderReportPng(row, bundle.resConfigXml(), bundle.resurlBack());
                    }
                } catch (RuntimeException e) {
                    log.warn("[活动页] 后台渲染长图失败 activityId={}: {}", activityId, e.toString());
                } finally {
                    renderQueued.remove(activityId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            renderQueued.remove(activityId);
        }
    }

    @PostMapping("/api/public/activity/subscribe")
    public Map<String, Object> subscribe(@RequestBody Map<String, Object> body) {
        if (!captcha.verify(str(body.get("cfToken")), str(body.get("gtPayload")))) {
            return Map.of("ok", false, "message", "人机验证未通过，请重试", "subscriberCount", email.count());
        }
        var r = email.subscribe(str(body.get("email")));
        return Map.of("ok", r.ok(), "message", r.message(), "subscriberCount", r.count());
    }

    @PostMapping("/api/public/activity/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody Map<String, Object> body) {
        String e = str(body.get("email"));
        if (e.isEmpty() || !e.contains("@")) {
            return Map.of("ok", false, "message", "请填写有效邮箱", "subscriberCount", email.count());
        }
        email.unsubscribe(e);
        return Map.of("ok", true, "message", "已退订", "subscriberCount", email.count());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** 剥 HTML 标签 + 常见实体（活动描述可能含富文本，feed 给机器人的纯文本版）。 */
    private static String stripHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    /** 活动名称搜索：标题或描述（剥 HTML）小写包含关键词。 */
    private static boolean matchesKeyword(ActivityRow a, String kwLower) {
        String t = a.title() == null ? "" : a.title();
        String d = a.description() == null ? "" : a.description();
        String hay = (t + " " + d).replaceAll("<[^>]+>", "").toLowerCase();
        return hay.contains(kwLower);
    }
}
