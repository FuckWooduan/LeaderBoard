package com.rankharvester.web;

import com.rankharvester.mail.MailTemplates;
import com.rankharvester.rank.store.MessageBoardStore;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 留言板刷屏治理：每小时把<b>最新 200 条可见留言</b>交给 AI，识别「同质刷屏」组并合并。
 *
 * <ul>
 *   <li>每组选 1 条<b>保留</b>（最早的那条），其余<b>下架并标记 MERGED</b>（原文保留，后台可追溯）；</li>
 *   <li>若其余条目含有信息增量，AI 可给出合并改写稿写回保留条，并累加「已合并 N 条」计数（前台展示）；</li>
 *   <li>对所有被改动（被合并 / 内容被改写）的留言人发<b>谷歌风格邮件</b>说明缘由与合并结果；</li>
 *   <li>保守原则：AI 不确定就不动；置顶留言与已有站长回复的留言一律不作为被吸收对象；
 *       AI 网关故障 → 本轮放弃，下轮再试（fail-open，不影响留言板可用性）。</li>
 * </ul>
 *
 * <p>与发布即审的 {@link MessageModerationService} 共用同一 OpenAI 兼容网关（{@code rankharvester.moderation.*}），
 * 模型独立配置（{@code rankharvester.msg-housekeeping.model}，默认用更强的模型做合并判断）。
 */
@Service
public class MessageHousekeepingService {

    private static final Logger log = LoggerFactory.getLogger(MessageHousekeepingService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int SCAN_LIMIT = 200;
    private static final int MIN_MESSAGES = 4;       // 留言太少没有刷屏可言
    private static final int MAX_CONTENT = 2000;     // 合并稿长度上限（与发帖一致）

    private static final String SYSTEM_PROMPT =
            """
            你是一个游戏玩家留言板的整理员，任务是治理「刷屏」：把高度相似、重复、或同一个人连发的同质留言合并成一条，\
            让留言板干净易读。

            判定规则（务必保守，宁可不合并，不可错合并）：
            1. 只合并「内容高度相似或重复」的留言（例如同一句话反复发、近似复读、同一诉求换皮连发）；
            2. 观点相同但表达各有内容的正常讨论【不要】合并；
            3. 每组合并：keep 选组内【最早（id 最小）】的一条；absorb 是其余被吸收的条目；
            4. mergedContent：若被吸收的条目相比 keep 没有任何信息增量，设为 null（保留原文不动）；\
            若有信息增量，以 keep 原文为基础，把增量自然并入（保持原作者语气，不虚构、不评论、不和谐内容，长度 ≤ 1500 字）；
            5. reason 用一句简短中文描述这组为什么算刷屏；
            6. 没有需要合并的就返回空数组。

            只输出一个 JSON 对象，不要任何其它文字：
            {"merges":[{"keep":<id>,"absorb":[<id>,...],"mergedContent":<字符串或null>,"reason":"..."}]}""";

    private final MessageBoardStore store;
    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MessageHousekeepingService(
            MessageBoardStore store,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String mailFrom,
            @Value("${rankharvester.moderation.base-url:}") String baseUrl,
            @Value("${rankharvester.moderation.api-key:}") String apiKey,
            @Value("${rankharvester.msg-housekeeping.model:gemini-3-pro}") String model,
            @Value("${rankharvester.msg-housekeeping.enabled:true}") boolean enabled) {
        this.store = store;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.enabled = enabled && !this.apiKey.isBlank() && !this.baseUrl.isBlank();
        log.info("[留言整理] {}（model={}，每小时检查最新 {} 条）", this.enabled ? "已启用" : "未启用", model, SCAN_LIMIT);
    }

    /** 每小时一轮（启动 10 分钟后开始；fixedDelay 保证不重入）。 */
    @Scheduled(initialDelay = 10 * 60 * 1000L, fixedDelay = 60 * 60 * 1000L)
    public void scheduledRun() {
        runOnce();
    }

    /** 跑一轮整理（后台「立即整理」按钮也走这里）。@return 给后台展示的结果信息 */
    public String runOnce() {
        if (!enabled) {
            return "整理任务未启用（缺 AI 网关配置）";
        }
        if (!running.compareAndSet(false, true)) {
            return "上一轮整理仍在进行中";
        }
        try {
            return doRun();
        } catch (Exception e) {
            log.warn("[留言整理] 本轮失败: {}", e.getMessage());
            return "本轮整理失败：" + e.getMessage();
        } finally {
            running.set(false);
        }
    }

    private String doRun() throws Exception {
        List<MessageBoardStore.Message> msgs = store.listLatestVisible(SCAN_LIMIT);
        if (msgs.size() < MIN_MESSAGES) {
            return "留言不足 " + MIN_MESSAGES + " 条，无需整理";
        }
        Map<Long, MessageBoardStore.Message> byId = new LinkedHashMap<>();
        msgs.forEach(m -> byId.put(m.id(), m));

        JsonNode merges = callMergePlan(msgs);
        if (merges == null || !merges.isArray() || merges.isEmpty()) {
            log.info("[留言整理] AI 检查 {} 条：无需合并", msgs.size());
            return "已检查最新 " + msgs.size() + " 条：无刷屏，无需合并";
        }

        int groups = 0;
        int absorbedTotal = 0;
        long now = System.currentTimeMillis();
        // email → 给该作者的通知段落列表（同一邮箱多条改动合并成一封）
        Map<String, List<String>> notices = new HashMap<>();

        for (JsonNode g : merges) {
            long keepId = g.path("keep").asLong(-1);
            String reason = g.path("reason").asString("").trim();
            String mergedContent = g.path("mergedContent").isNull() ? null : g.path("mergedContent").asString(null);
            if (mergedContent != null && (mergedContent.isBlank() || mergedContent.length() > MAX_CONTENT)) {
                mergedContent = null; // 不合规的改写稿直接放弃改写，仅做下架合并
            }
            MessageBoardStore.Message keep = byId.get(keepId);
            if (keep == null) {
                continue;
            }
            // 收集合法的被吸收条目：必须在本批内、不是 keep、未置顶、没有站长回复
            var absorbIds = new HashSet<Long>();
            for (JsonNode idNode : g.path("absorb")) {
                long id = idNode.asLong(-1);
                MessageBoardStore.Message m = byId.get(id);
                if (m != null && id != keepId && !m.pinned()
                        && (m.replyText() == null || m.replyText().isBlank())) {
                    absorbIds.add(id);
                }
            }
            if (absorbIds.isEmpty()) {
                continue;
            }
            // 先更新保留条（内容改写 + 累加计数），再隐藏被吸收条：即便中途失败，也不会出现
            // 「留言已消失但保留条计数/内容未更新」的不一致（计数宁可偏小，也不让用户看到错乱）。
            store.applyMergeKeep(keepId, mergedContent, absorbIds.size());
            if (mergedContent != null && !mergedContent.equals(keep.content())) {
                addNotice(notices, keep.email(), keepNoticeHtml(keep, mergedContent, absorbIds.size()));
            }
            int absorbed = 0;
            for (long id : absorbIds) {
                if (store.applyMergeAbsorb(id, keepId, reason, now)) {
                    absorbed++;
                    MessageBoardStore.Message m = byId.get(id);
                    addNotice(notices, m.email(), absorbNoticeHtml(m, keep, reason));
                }
            }
            groups++;
            absorbedTotal += absorbed;
            log.info("[留言整理] 合并组：保留 #{}，吸收 {} 条（{}）{}", keepId, absorbed, reason,
                    mergedContent != null ? "，内容已改写" : "");
        }

        notices.forEach(this::sendNoticeMail);
        String summary = groups == 0
                ? "已检查最新 " + msgs.size() + " 条：无可执行的合并"
                : "已合并 " + groups + " 组刷屏留言（共收起 " + absorbedTotal + " 条），相关留言人已邮件通知";
        log.info("[留言整理] {}", summary);
        return summary;
    }

    // ── AI 调用 ───────────────────────────────────────────────────────────────

    private JsonNode callMergePlan(List<MessageBoardStore.Message> msgs) throws Exception {
        var items = new ArrayList<Map<String, Object>>(msgs.size());
        for (var m : msgs) {
            items.add(Map.of("id", m.id(), "nickname", m.nickname() == null ? "游客" : m.nickname(),
                    "content", m.content(), "createdAt", m.createdAt()));
        }
        String body = JSON.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content",
                                "以下是留言板最新 " + msgs.size() + " 条可见留言（JSON 数组，新→旧）：\n"
                                        + JSON.writeValueAsString(items)))));
        var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("AI 网关 HTTP " + resp.statusCode());
        }
        String text = JSON.readTree(resp.body())
                .path("choices").path(0).path("message").path("content").asString("");
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("AI 响应不含 JSON");
        }
        return JSON.readTree(text.substring(a, b + 1)).path("merges");
    }

    // ── 邮件通知（谷歌风格）────────────────────────────────────────────────────

    private static void addNotice(Map<String, List<String>> notices, String email, String html) {
        if (email == null || email.isBlank()) {
            return;
        }
        notices.computeIfAbsent(email.trim(), k -> new ArrayList<>()).add(html);
    }

    /** 被吸收（下架合并）的通知段落。 */
    private static String absorbNoticeHtml(MessageBoardStore.Message m, MessageBoardStore.Message keep,
            String reason) {
        var sb = new StringBuilder();
        sb.append(MailTemplates.paragraph("你的这条留言（#" + m.id() + "）与多条相似留言<b>合并展示</b>了："));
        sb.append(MailTemplates.quote(m.content(), MailTemplates.BLUE));
        if (reason != null && !reason.isBlank()) {
            sb.append(MailTemplates.kv("合并原因", MailTemplates.escapeHtml(reason)));
        }
        sb.append(MailTemplates.kv("合并到", "留言 #" + keep.id() + "（" + MailTemplates.escapeHtml(
                keep.nickname() == null ? "游客" : keep.nickname()) + " 发布）"));
        return sb.toString();
    }

    /** 保留条内容被 AI 改写的通知段落。 */
    private static String keepNoticeHtml(MessageBoardStore.Message keep, String mergedContent, int absorbed) {
        return MailTemplates.paragraph("你的留言（#" + keep.id() + "）吸收了 <b>" + absorbed
                        + "</b> 条相似留言的内容，合并后展示为：")
                + MailTemplates.quote(mergedContent, MailTemplates.BLUE);
    }

    private void sendNoticeMail(String email, List<String> sections) {
        var body = new StringBuilder();
        body.append(MailTemplates.paragraph("你好："));
        body.append(MailTemplates.paragraph(
                "为了让留言板保持干净易读，系统的 AI 整理员会定期把<b>高度相似的刷屏留言</b>合并展示。"
                        + "本次整理涉及你的留言，改动如下："));
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) {
                body.append(MailTemplates.divider());
            }
            body.append(sections.get(i));
        }
        body.append(MailTemplates.button("查看留言板", "https://example.com/msg"));
        body.append(MailTemplates.small("被合并的留言只是收起展示，原文仍有存档；如认为合并有误，欢迎在留言板反馈。"));
        String html = MailTemplates.wrap("你的留言被合并展示了", "留言板整理通知", body.toString());
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(email);
            helper.setSubject("【strikegod 留言板】你的留言已合并展示");
            helper.setText(html, true);
            mailSender.send(mime);
            log.info("[留言整理] 合并通知已发送至 {}", email);
        } catch (Exception e) {
            log.warn("[留言整理] 合并通知发送失败({}): {}", email, e.getMessage());
        }
    }
}
