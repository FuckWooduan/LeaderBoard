package com.rankharvester.rank.slice;

import com.rankharvester.mail.MailTemplates;
import com.rankharvester.rank.model.rows.ReducedRankRow;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 分区排行榜邮件订阅：订阅某 (rankKey, 分区) 里某玩家，<b>排名变动即发邮件</b>。
 *
 * <p>{@link PartitionRankFetchService} 每抓完一个分区就调 {@link #notifyPartition} 比对订阅。
 */
@Service
public class SliceSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SliceSubscriptionService.class);
    /** 防轰炸：同一订阅两封「排名变动」通知之间的最小间隔（分区榜抓取频繁、名次会小幅抖动）。 */
    private static final long NOTIFY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    private final SliceSubscriptionStore subStore;
    private final PartitionRankStore rankStore;
    private final RankSliceStore sliceStore;
    private final JavaMailSender mailSender;
    private final String from;

    public SliceSubscriptionService(
            SliceSubscriptionStore subStore,
            PartitionRankStore rankStore,
            RankSliceStore sliceStore,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String from) {
        this.subStore = subStore;
        this.rankStore = rankStore;
        this.sliceStore = sliceStore;
        this.mailSender = mailSender;
        this.from = from;
    }

    /** 订阅：记录订阅时该玩家在该分区的当前名次（下次变动才通知），并立刻发确认邮件。 */
    public void subscribe(String email, String rankKey, int partition, String charName) {
        Integer cur = currentRankOf(rankKey, partition, charName);
        subStore.subscribe(email, rankKey, partition, charName, cur);
        log.info("新增订阅: {} → {}#{} 玩家[{}] 当前名次 {}", email, rankKey, partition, charName, cur);
        try {
            String label = labelOf(rankKey);
            String curStr = cur == null ? "暂未上榜" : ("第 " + cur + " 名");
            // 统一谷歌风格模板：关键信息 kv 呈现，动作按钮指向分区榜
            String body = MailTemplates.paragraph("订阅成功！之后该玩家在此分区的排名发生变动时，会自动邮件通知你。")
                    + MailTemplates.kv("排行榜", MailTemplates.escapeHtml(label))
                    + MailTemplates.kv("分区", String.valueOf(partition + 1))
                    + MailTemplates.kv("角色", MailTemplates.escapeHtml(charName))
                    + MailTemplates.kv("当前名次", MailTemplates.escapeHtml(curStr))
                    + MailTemplates.button("查看分区排行榜", "https://example.com/slice")
                    + MailTemplates.small("退订：在网站对应分区取消订阅即可。");
            sendHtml(email, "【修仙榜】订阅成功：" + charName,
                    MailTemplates.wrap("分区排名订阅成功", "📈 排名订阅成功", body));
            log.info("已发订阅确认邮件 → {}", email);
        } catch (RuntimeException e) {
            log.warn("订阅确认邮件发送失败 {}: {}", email, e.getMessage());
        }
    }

    public void unsubscribe(String email, String rankKey, int partition, String charName) {
        subStore.unsubscribe(email, rankKey, partition, charName);
    }

    /** 测试邮件通道（管理用）。 */
    public void sendTest(String to) {
        String body = MailTemplates.paragraph("这是一封测试邮件，收到即说明分区排行榜的邮件订阅通道正常。")
                + MailTemplates.button("打开分区排行榜", "https://example.com/slice");
        sendHtml(to, "【氪了就能赢·修仙榜】订阅通道测试",
                MailTemplates.wrap("邮件订阅通道测试", "✅ 邮件通道测试", body));
        log.info("已发测试邮件 → {}", to);
    }

    /** 批量预测完成摘要：把识别出的隐藏玩家身份发给用户（预测分数已删除，不再展示）。 */
    public void sendPredictionDigest(String to, String rankKey, List<HiddenPredictionStore.Pred> top,
            String label, int identityDone, int scoreDone, int total) {
        var body = new StringBuilder();
        body.append(MailTemplates.paragraph(
                "您发起的「<b>" + MailTemplates.escapeHtml(label) + "</b>」隐藏玩家预测已完成。"));
        body.append(MailTemplates.kv("预测隐藏槽位", total + " 个"));
        body.append(MailTemplates.kv("识别出身份", identityDone + " 个"));
        body.append(MailTemplates.divider());
        body.append(MailTemplates.paragraph("部分识别玩家："));
        body.append("<ol style=\"margin:10px 0;padding-left:22px\">");
        for (var p : top) {
            if (p.name() == null) {
                continue;
            }
            body.append("<li style=\"margin:4px 0\"><b>").append(MailTemplates.escapeHtml(p.name()))
                    .append("</b><span style=\"color:#5f6368\">（")
                    .append(MailTemplates.escapeHtml(p.via())).append("）</span></li>");
        }
        body.append("</ol>");
        body.append(MailTemplates.button("查看预测结果", "https://example.com/slice"));
        body.append(MailTemplates.small("结果为 AI/规则预测，仅供参考。"));
        sendHtml(to, "【氪了就能赢·修仙榜】" + label + " 隐藏玩家预测完成",
                MailTemplates.wrap(label + " 隐藏玩家预测完成", "🔮 隐藏玩家预测完成", body.toString()));
        log.info("已发预测摘要邮件 → {}：{} 前{}名", to, label, top.size());
    }

    /** 即刻抓取完成通知：告知用户该榜抓了多少分区、多少行。 */
    public void sendFetchDone(String to, String rankKey, int totalParts, int okParts, long rows) {
        String label = labelOf(rankKey);
        String body = MailTemplates.paragraph("您发起的「即刻抓取」已完成：")
                + MailTemplates.kv("排行榜", MailTemplates.escapeHtml(label)
                        + "<span style=\"color:#5f6368\">（rankKey " + MailTemplates.escapeHtml(rankKey) + "）</span>")
                + MailTemplates.kv("分区", "成功 " + okParts + " / " + totalParts + " 个")
                + MailTemplates.kv("本次写入", rows + " 行")
                + MailTemplates.button("查看分区排行榜", "https://example.com/slice");
        sendHtml(to, "【氪了就能赢·修仙榜】" + label + " 即刻抓取完成",
                MailTemplates.wrap(label + " 即刻抓取完成", "⚡ 即刻抓取完成", body));
        log.info("已发即刻抓取完成通知 → {}：{} 分区 {}/{} 行 {}", to, label, okParts, totalParts, rows);
    }

    /** 抓完一个分区后比对订阅：名次有变即发邮件并更新 last_rank。 */
    public void notifyPartition(String rankKey, int partition, List<ReducedRankRow> rows) {
        var subs = subStore.forPartition(rankKey, partition);
        if (subs.isEmpty()) {
            return;
        }
        String label = labelOf(rankKey);
        long now = System.currentTimeMillis();
        for (var sub : subs) {
            Integer newRank = rankInRows(rows, sub.charName());
            if (Objects.equals(newRank, sub.lastRank())) {
                continue; // 与上次「已通知」名次相同，不发
            }
            // 防轰炸：距上次通知不足冷却时间则跳过（分区榜抓取频繁、名次小幅抖动会反复触发）。
            // 名次真的稳定变化时，冷却过后下一次抓取仍会比对并通知，不会漏。
            if (sub.lastNotifiedAt() > 0 && now - sub.lastNotifiedAt() < NOTIFY_COOLDOWN_MS) {
                continue;
            }
            try {
                sendChange(sub.email(), label, rankKey, partition, sub.charName(), sub.lastRank(), newRank);
                subStore.updateRank(sub.email(), rankKey, partition, sub.charName(), newRank);
            } catch (RuntimeException e) {
                log.warn("订阅通知发送失败 {} [{}]: {}", sub.email(), sub.charName(), e.getMessage());
            }
        }
    }

    private void sendChange(String to, String label, String rankKey, int partition,
            String charName, Integer oldRank, Integer newRank) {
        String oldStr = oldRank == null ? "未上榜" : ("第 " + oldRank + " 名");
        String newStr = newRank == null ? "已掉出榜单" : ("第 " + newRank + " 名");
        // 升降趋势：上升绿色、下降红色
        String trend = "";
        if (oldRank != null && newRank != null) {
            trend = newRank < oldRank
                    ? "&nbsp;<span style=\"color:#188038\">↑ 上升 " + (oldRank - newRank) + " 名</span>"
                    : newRank > oldRank
                            ? "&nbsp;<span style=\"color:#c5221f\">↓ 下降 " + (newRank - oldRank) + " 名</span>"
                            : "";
        }
        String body = MailTemplates.paragraph("您订阅的玩家排名发生变动：")
                + MailTemplates.kv("排行榜", MailTemplates.escapeHtml(label))
                + MailTemplates.kv("分区", String.valueOf(partition + 1))
                + MailTemplates.kv("角色", MailTemplates.escapeHtml(charName))
                + MailTemplates.kv("排名", MailTemplates.escapeHtml(oldStr) + " → <b>"
                        + MailTemplates.escapeHtml(newStr) + "</b>" + trend)
                + MailTemplates.button("查看分区排行榜", "https://example.com/slice");
        sendHtml(to, "【氪了就能赢·修仙榜】" + charName + " 排名变动通知",
                MailTemplates.wrap(charName + " 排名变动", "📊 排名变动通知", body));
        log.info("已发订阅通知 → {}：{} {} → {}", to, charName, oldStr, newStr);
    }

    /** 发送 HTML 邮件。MessagingException 包装为运行时异常，保持与原 SimpleMailMessage 一致的异常传播行为。 */
    private void sendHtml(String to, String subject, String html) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
        } catch (MessagingException e) {
            throw new IllegalStateException("构造邮件失败: " + e.getMessage(), e);
        }
        mailSender.send(mime);
    }

    private static Integer rankInRows(List<ReducedRankRow> rows, String charName) {
        for (ReducedRankRow r : rows) {
            if (charName.equals(r.infoCharName())) {
                return r.rank();
            }
        }
        return null; // 不在该分区榜
    }

    private Integer currentRankOf(String rankKey, int partition, String charName) {
        for (var row : rankStore.page(rankKey, partition, 1, 1000)) {
            if (charName.equals(String.valueOf(row.get("char_name")))) {
                Object rk = row.get("rank");
                return rk instanceof Number n ? n.intValue() : null;
            }
        }
        return null;
    }

    private String labelOf(String rankKey) {
        return sliceStore.listKeys().stream()
                .filter(k -> k.rankKey().equals(rankKey))
                .findFirst()
                .map(k -> (k.label() == null || k.label().isBlank()) ? rankKey : k.label())
                .orElse(rankKey);
    }
}
