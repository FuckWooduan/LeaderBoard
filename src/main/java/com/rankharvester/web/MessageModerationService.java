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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 留言板 AI 审核（先显示后审核）。
 *
 * <p>留言发布后异步送 OpenAI 兼容接口审核。规则：允许普通骂人/脏话/对游戏官方的激烈吐槽；
 * 不通过的只有：色情赌博毒品、涉及政治、其他违反中华人民共和国法律的内容。
 * 审核不通过 → 下架（visible=false）并给留言者发邮件告知原因；接口异常 → 保持显示（fail-open，标记 SKIPPED）。
 */
@Service
public class MessageModerationService {

    private static final Logger log = LoggerFactory.getLogger(MessageModerationService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM_PROMPT =
            """
            你是一个游戏玩家留言板的内容审核员。这个留言板专供玩家吐槽某游戏的官方运营，\
            允许普通的骂人、脏话、嘲讽、阴阳怪气和激烈的负面评价（哪怕话很难听也算通过）。\
            只有以下内容不通过：
            1. 色情、赌博、毒品相关内容；
            2. 涉及政治（政治人物、政治事件、政治立场、意识形态等）；
            3. 其他违反中华人民共和国法律的内容（如暴恐、诈骗引流、传播他人隐私、真实人身威胁等）。
            只输出一个 JSON 对象，不要任何其它文字：{"pass":true 或 false,"reason":"不通过时用一句简短中文说明原因；通过时为空字符串"}""";

    private final MessageBoardStore store;
    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "msg-moderation");
        t.setDaemon(true);
        return t;
    });

    public MessageModerationService(
            MessageBoardStore store,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String mailFrom,
            @Value("${rankharvester.moderation.base-url:}") String baseUrl,
            @Value("${rankharvester.moderation.api-key:}") String apiKey,
            @Value("${rankharvester.moderation.model:gemini-3.5-flash}") String model) {
        this.store = store;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.enabled = !this.apiKey.isBlank() && !this.baseUrl.isBlank();
        log.info("[留言审核] {}（model={}）", enabled ? "已启用" : "未启用（无 api-key）", model);
    }

    /** 异步审核一条留言（先显示后审核：调用方已入库并对外可见）。 */
    public void reviewAsync(long id, String nickname, String content, String email) {
        if (!enabled) {
            store.markReview(id, "SKIPPED", "审核未启用", System.currentTimeMillis());
            return;
        }
        executor.submit(() -> review(id, nickname, content, email));
    }

    private void review(long id, String nickname, String content, String email) {
        Verdict v = null;
        for (int attempt = 1; attempt <= 2 && v == null; attempt++) {
            try {
                v = callModeration(content);
            } catch (Exception e) {
                log.warn("[留言审核] #{} 第{}次调用失败: {}", id, attempt, e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        if (v == null) {
            // 审核服务不可达 → 保持显示，标记跳过（fail-open，与极验同思路：组件故障不阻断主功能）
            store.markReview(id, "SKIPPED", "审核服务不可达", now);
            return;
        }
        if (v.pass()) {
            store.markReview(id, "PASS", null, now);
            log.info("[留言审核] #{} 通过", id);
        } else {
            store.rejectByReview(id, v.reason(), now);
            log.info("[留言审核] #{} 不通过: {}", id, v.reason());
            sendRejectMail(email, nickname, content, v.reason());
        }
    }

    private record Verdict(boolean pass, String reason) {}

    private Verdict callModeration(String content) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", "待审核留言：\n" + content))));
        var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + snip(resp.body()));
        }
        String text = JSON.readTree(resp.body())
                .path("choices").path(0).path("message").path("content").asString("");
        // 模型可能裹 markdown 代码块或附带文字，截取第一个 { 到最后一个 } 再解析
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("响应不含 JSON: " + snip(text));
        }
        var node = JSON.readTree(text.substring(a, b + 1));
        if (!node.has("pass")) {
            throw new IllegalStateException("响应缺 pass 字段: " + snip(text));
        }
        return new Verdict(node.path("pass").asBoolean(false), node.path("reason").asString("").trim());
    }

    private void sendRejectMail(String email, String nickname, String content, String reason) {
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(email);
            helper.setSubject("【strikegod 留言板】你的留言未通过审核");
            helper.setText(buildRejectHtml(nickname, content, reason), true);
            mailSender.send(mime);
            log.info("[留言审核] 拒绝通知已发送至 {}", email);
        } catch (Exception e) {
            log.warn("[留言审核] 拒绝通知发送失败({}): {}", email, e.getMessage());
        }
    }

    private static String buildRejectHtml(String nickname, String content, String reason) {
        String body = MailTemplates.paragraph("你好，" + MailTemplates.escapeHtml(nickname) + "：")
                + MailTemplates.paragraph("你在 example.com 留言板发布的以下留言，经 AI 审核未通过，已被下架：")
                + MailTemplates.quote(content, "#c5221f")
                + MailTemplates.kv("原因", MailTemplates.escapeHtml(
                        reason == null || reason.isBlank() ? "包含不允许的内容" : reason))
                + MailTemplates.noteBox("吐槽游戏、骂官方都没问题；只有黄赌毒、涉政及其他违法内容会被下架。欢迎修改后重新发布。")
                + MailTemplates.button("回到留言板", "https://example.com/msg");
        return MailTemplates.wrap("你的留言未通过审核", "留言未通过审核", body);
    }

    private static String snip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
