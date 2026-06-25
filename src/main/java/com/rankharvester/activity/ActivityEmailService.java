package com.rankharvester.activity;

import com.rankharvester.mail.MailTemplates;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 「最新活动通知」邮件订阅服务：
 *
 * <ul>
 *   <li>{@link #subscribe} —— 记录订阅邮箱并立刻发一封确认邮件。</li>
 *   <li>{@link #sendActivityBatch} —— 一批新活动合并成<b>一封</b>邮件，<b>并行</b>发给所有订阅者（失败重试）。</li>
 * </ul>
 */
@Service
public class ActivityEmailService {

    private static final Logger log = LoggerFactory.getLogger(ActivityEmailService.class);

    /** 活动邮件并行发送并发度（有界，避免压垮 SMTP；SMTP 较敏感，别开太高）。 */
    private static final int MAIL_CONCURRENCY = 6;
    /** 单个收件人发送失败的最大尝试次数（含首次；之间按 1s、2s 退避重试）。 */
    private static final int MAIL_MAX_ATTEMPTS = 3;

    private final ActivityEmailSubStore store;
    private final JavaMailSender mailSender;
    private final String from;

    public ActivityEmailService(
            ActivityEmailSubStore store,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String from) {
        this.store = store;
        this.mailSender = mailSender;
        this.from = from;
    }

    public int count() {
        return store.count();
    }

    public java.util.List<String> allEmails() {
        return store.allEmails();
    }

    /** 清理订阅表里的无效邮箱（缺本地名/含逗号/全角等脏数据）。返回 {total, removed:[...], remaining}。 */
    public java.util.Map<String, Object> cleanInvalid() {
        var all = store.allEmails();
        var removed = new java.util.ArrayList<String>();
        for (String e : all) {
            if (e == null || !EMAIL_RE.matcher(e.trim()).matches()) {
                if (e != null) {
                    store.unsubscribe(e);
                }
                removed.add(String.valueOf(e));
            }
        }
        return java.util.Map.of("total", all.size(), "removed", removed, "remaining", store.count());
    }

    /** 批量通知项：一条新活动 + 它的长图路径。 */
    public record BatchItem(int activityId, String title, String startTime, String endTime, Path png) {}

    /**
     * 把一批新活动聚合成<b>一封</b>邮件发给每个收件人：正文是编号的活动名称(+时间区间，若有)列表，
     * 每条活动的<b>长图作为附件</b>（长图很长，内联笨重）。返回成功发出的收件人数。
     */
    public int sendActivityBatch(java.util.List<String> recipients, java.util.List<BatchItem> items) {
        if (recipients == null || recipients.isEmpty() || items == null || items.isEmpty()) {
            return 0;
        }
        var attach = new java.util.ArrayList<byte[]>(); // 一次读，多收件人复用
        for (BatchItem it : items) {
            byte[] b = null;
            try {
                if (it.png() != null && Files.exists(it.png())) {
                    b = Files.readAllBytes(it.png());
                }
            } catch (Exception e) {
                log.warn("[活动邮件] 读长图失败 activityId={}: {}", it.activityId(), e.toString());
            }
            attach.add(b);
        }
        String html = buildBatchHtml(items);
        String subject = "【新活动】" + items.size() + " 个新活动上线";

        // 先过滤脏地址（缺本地名/含逗号/全角等），不让一个坏地址污染整批
        var valid = new java.util.ArrayList<String>();
        for (String to : recipients) {
            if (to != null && EMAIL_RE.matcher(to.trim()).matches()) {
                valid.add(to.trim());
            }
        }
        if (valid.isEmpty()) {
            return 0;
        }
        // 并行发送（有界并发，避免压垮 SMTP）+ 单个收件人失败重试
        int concurrency = Math.min(MAIL_CONCURRENCY, valid.size());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency, r -> {
            var t = new Thread(r, "act-mail");
            t.setDaemon(true);
            return t;
        });
        var sent = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (String to : valid) {
                futures.add(pool.submit(() -> {
                    if (sendBatchToOne(to, subject, html, items, attach)) {
                        sent.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) {
                try {
                    f.get(); // 等全部发完；单任务异常已在内部记录，不影响其余
                } catch (Exception ignore) {
                    // 忽略：失败计数以 sent 为准
                }
            }
        } finally {
            pool.shutdown();
        }
        log.info("[活动邮件] 批量({}个活动)并行已发 {}/{} 收件人", items.size(), sent.get(), valid.size());
        return sent.get();
    }

    /** 给单个收件人发合并邮件，失败按 1s、2s 退避重试；成功返回 true。线程安全（每次新建 MimeMessage）。 */
    private boolean sendBatchToOne(String to, String subject, String html,
            java.util.List<BatchItem> items, java.util.List<byte[]> attach) {
        for (int attempt = 1; attempt <= MAIL_MAX_ATTEMPTS; attempt++) {
            try {
                MimeMessage mime = mailSender.createMimeMessage();
                var helper = new MimeMessageHelper(mime, true, "UTF-8");
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                for (int i = 0; i < items.size(); i++) {
                    byte[] b = attach.get(i);
                    if (b != null) {
                        String name = String.format("%02d_%s.png", i + 1, safeFile(strip(items.get(i).title())));
                        helper.addAttachment(name, new ByteArrayResource(b), "image/png");
                    }
                }
                mailSender.send(mime);
                return true;
            } catch (Exception e) {
                log.warn("[活动邮件] 发给 {} 失败(第 {}/{} 次): {}", to, attempt, MAIL_MAX_ATTEMPTS, e.toString());
                if (attempt < MAIL_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1000L * attempt); // 1s、2s 退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private static String safeFile(String s) {
        String t = (s == null || s.isBlank()) ? "活动" : s;
        return t.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    /** 批量新活动通知 HTML（统一谷歌风格模板：编号列表 + 附件提示）。 */
    private static String buildBatchHtml(java.util.List<BatchItem> items) {
        var body = new StringBuilder();
        body.append(MailTemplates.paragraph("检测到 <b>" + items.size() + "</b> 个新活动上线："));
        body.append("<ol style=\"margin:10px 0;padding-left:22px\">");
        for (BatchItem it : items) {
            String title = strip(it.title());
            if (title.isEmpty()) {
                title = "活动" + it.activityId();
            }
            String st = it.startTime() == null ? "" : it.startTime();
            String et = it.endTime() == null ? "" : it.endTime();
            String time = (!st.isBlank() || !et.isBlank())
                    ? " <span style=\"color:#9aa0a6\">(" + MailTemplates.escapeHtml(st) + " ~ "
                            + MailTemplates.escapeHtml(et) + ")</span>"
                    : "";
            body.append("<li style=\"margin:6px 0\"><b>").append(MailTemplates.escapeHtml(title)).append("</b>")
                    .append(time).append("</li>");
        }
        body.append("</ol>");
        body.append(MailTemplates.noteBox("📎 每个活动的<b>长图</b>见邮件<b>附件</b>（长图里含活动时间、奖励详情）。"));
        body.append(MailTemplates.button("查看全部活动", "https://example.com/activity"));
        body.append(MailTemplates.small("退订：网站「活动通知」页面填同一邮箱点退订。"));
        return MailTemplates.wrap(items.size() + " 个新活动上线", "🎮 检测到 " + items.size() + " 个新活动",
                body.toString());
    }

    /** 订阅结果。 */
    public record SubResult(boolean ok, String message, int count) {}

    /**
     * 订阅最新活动通知：记录邮箱 + 立刻发确认邮件。重复订阅也回 ok（幂等，提示已订阅）。
     */
    /** 合法邮箱：本地名 + @ + 域名 + 顶级域，只允许 ASCII 邮箱字符（拒绝缺本地名/含逗号/全角/CJK 等脏数据）。 */
    public static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public SubResult subscribe(String email) {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty() || e.length() > 200 || !EMAIL_RE.matcher(e).matches()) {
            return new SubResult(false, "请填写有效邮箱", store.count());
        }
        boolean fresh;
        try {
            fresh = store.subscribe(e);
        } catch (RuntimeException ex) {
            log.warn("[活动订阅] 写库失败 {}: {}", e, ex.getMessage());
            return new SubResult(false, "订阅失败，请稍后重试", store.count());
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(e);
            helper.setSubject("【活动通知】订阅成功 🔔");
            helper.setText(buildConfirmHtml(), true); // HTML 正文
            mailSender.send(mime);
        } catch (Exception ex) {
            log.warn("[活动订阅] 确认邮件发送失败 {}: {}", e, ex.getMessage());
            return new SubResult(true, fresh ? "订阅成功（确认邮件发送失败，但订阅已生效）" : "你已订阅过了",
                    store.count());
        }
        return new SubResult(true, fresh ? "订阅成功，已发确认邮件到你的邮箱" : "你已订阅过了（已重发确认邮件）",
                store.count());
    }

    public void unsubscribe(String email) {
        if (email != null && !email.isBlank()) {
            store.unsubscribe(email.trim());
        }
    }

    /** 订阅确认邮件 HTML（统一谷歌风格模板）。 */
    private static String buildConfirmHtml() {
        String body = MailTemplates.paragraph("你已成功订阅<b>最新活动通知</b>。")
                + MailTemplates.paragraph("从此刻起，一旦检测到游戏上线<b style=\"color:" + MailTemplates.BLUE
                        + "\">新活动</b>，我们就会第一时间把该活动的<b>长图</b>发到这个邮箱。")
                + MailTemplates.noteBox("⏱️ 每 1 分钟检测一次新活动 · 只发真正新出现的活动，存量活动不会打扰你。")
                + MailTemplates.button("查看最新活动", "https://example.com/activity")
                + MailTemplates.small("退订：在网站「活动通知」页面填同一邮箱点退订即可。");
        return MailTemplates.wrap("活动通知订阅成功", "🔔 订阅成功", body);
    }

    /** 剥 HTML 标签 + 解实体（活动描述可能含富文本）。 */
    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }
}
