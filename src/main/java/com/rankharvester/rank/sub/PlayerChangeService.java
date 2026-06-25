package com.rankharvester.rank.sub;

import com.rankharvester.mail.MailTemplates;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 玩家「变化订阅」服务：按 GCID 订阅某玩家的改名 / VIP 提升 / 境界提升，每次同服战力榜
 * {@code REDUCED:382:<loginId>} 刷新后比对，变化即邮件通知。
 */
@Service
public class PlayerChangeService {

    private static final Logger log = LoggerFactory.getLogger(PlayerChangeService.class);
    private static final String[] REALM = {"境界I·荣光之主", "境界II·恒星之主", "境界III·星界域主"};

    private final PlayerSubStore subStore;
    private final Connection conn;
    private final JavaMailSender mailSender;
    private final String from;

    public PlayerChangeService(
            PlayerSubStore subStore,
            Connection duckDbConnection,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String from) {
        this.subStore = subStore;
        this.conn = duckDbConnection;
        this.mailSender = mailSender;
        this.from = from;
    }

    private record State(long gcId, String name, Integer vip, Integer realm) {}

    private static String code(String loginId) {
        return "REDUCED:382:" + loginId;
    }

    /** 订阅结果。 */
    public record SubResult(boolean ok, String message, Long gcId) {}

    /**
     * 订阅某玩家变化。按角色名在该服同服战力榜里定位 → 取 gcId/当前 VIP/境界 → 记录基线 + 发确认邮件。
     * 玩家不在同服战力榜（未上榜）则无法订阅。
     */
    public SubResult subscribe(String email, String loginId, String charName,
            boolean rename, boolean vip, boolean realm) {
        if (!rename && !vip && !realm) {
            return new SubResult(false, "请至少勾选一项通知（改名/VIP/境界）", null);
        }
        State s = findByName(loginId, charName);
        if (s == null) {
            return new SubResult(false, "该玩家暂不在「同服战力榜」上，目前只能订阅上榜玩家", null);
        }
        long now = System.currentTimeMillis();
        subStore.subscribe(email, s.gcId(), loginId, s.name(), s.vip(), s.realm(), rename, vip, realm, now);
        log.info("新增玩家订阅: {} → gc={} [{}] vip={} realm={} 项(改名{}/VIP{}/境界{})",
                email, s.gcId(), s.name(), s.vip(), s.realm(), rename, vip, realm);
        sendConfirm(email, s, rename, vip, realm);
        return new SubResult(true, "订阅成功，已发确认邮件到 " + email, s.gcId());
    }

    public void unsubscribe(String email, long gcId) {
        subStore.unsubscribe(email, gcId);
    }

    /**
     * 某服同服战力榜刷新后调用：比对该服所有订阅的玩家最新状态，改名/VIP/境界发生（且被订阅的）变化即发邮件。
     */
    public void checkServer(String loginId) {
        var subs = subStore.forServer(loginId);
        if (subs.isEmpty()) {
            return;
        }
        Map<Long, State> latest = loadAll(loginId);
        long now = System.currentTimeMillis();
        for (var sub : subs) {
            State cur = latest.get(sub.gcId());
            if (cur == null) {
                continue; // 本次未上榜，无法判断变化，保留基线
            }
            var changes = new java.util.ArrayList<String>();
            if (sub.subRename() && cur.name() != null && !Objects.equals(cur.name(), sub.lastName())) {
                changes.add("改名：" + sub.lastName() + " → " + cur.name());
            }
            if (sub.subVip() && cur.vip() != null && sub.lastVip() != null && cur.vip() > sub.lastVip()) {
                changes.add("VIP 提升：V" + sub.lastVip() + " → V" + cur.vip());
            }
            if (sub.subRealm() && cur.realm() != null && sub.lastRealm() != null && cur.realm() > sub.lastRealm()) {
                changes.add("境界提升：" + realmName(sub.lastRealm()) + " → " + realmName(cur.realm()));
            }
            if (changes.isEmpty()) {
                // 仍要推进基线（例如名字变了但没订阅改名，下次不重复判）
                subStore.updateState(sub.email(), sub.gcId(), cur.name(), cur.vip(), cur.realm(), now);
                continue;
            }
            try {
                sendChange(sub.email(), cur.name(), changes);
                subStore.updateState(sub.email(), sub.gcId(), cur.name(), cur.vip(), cur.realm(), now);
            } catch (RuntimeException e) {
                log.warn("玩家变化通知发送失败 {} gc={}: {}", sub.email(), sub.gcId(), e.getMessage());
            }
        }
    }

    // ── 查询 reduced_rank（同服战力榜） ─────────────────────────────────────────

    private State findByName(String loginId, String charName) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT score_gc_id, info_char_name, info_blue_vip_level, score_rebirth FROM reduced_rank"
                    + " WHERE leaderboard_code=? AND info_char_name=? LIMIT 1")) {
                ps.setString(1, code(loginId));
                ps.setString(2, charName);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new State(rs.getLong(1), rs.getString(2), intOrNull(rs, 3), intOrNull(rs, 4));
                    }
                }
            } catch (SQLException e) {
                log.warn("findByName 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private Map<Long, State> loadAll(String loginId) {
        var map = new HashMap<Long, State>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT score_gc_id, info_char_name, info_blue_vip_level, score_rebirth FROM reduced_rank"
                    + " WHERE leaderboard_code=?")) {
                ps.setString(1, code(loginId));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long gc = rs.getLong(1);
                        map.put(gc, new State(gc, rs.getString(2), intOrNull(rs, 3), intOrNull(rs, 4)));
                    }
                }
            } catch (SQLException e) {
                log.warn("loadAll 失败: {}", e.getMessage());
            }
        }
        return map;
    }

    // ── 邮件 ───────────────────────────────────────────────────────────────────

    private void sendConfirm(String email, State s, boolean rename, boolean vip, boolean realm) {
        try {
            var items = new java.util.ArrayList<String>();
            if (rename) {
                items.add("改名");
            }
            if (vip) {
                items.add("VIP 提升");
            }
            if (realm) {
                items.add("境界提升");
            }
            // 统一谷歌风格模板：玩家当前状态 kv 呈现，动作按钮指向玩家查询页
            String body = MailTemplates.paragraph("订阅成功！每次同服战力榜刷新后，若该玩家发生你订阅的变化，会自动邮件通知你。")
                    + MailTemplates.kv("玩家", MailTemplates.escapeHtml(s.name()))
                    + MailTemplates.kv("当前 VIP", "V" + (s.vip() == null ? "?" : s.vip()))
                    + MailTemplates.kv("当前境界", MailTemplates.escapeHtml(realmName(s.realm())))
                    + MailTemplates.kv("通知项", MailTemplates.escapeHtml(String.join("、", items)))
                    + MailTemplates.noteBox("仅能检测在「同服战力榜」上的玩家。")
                    + MailTemplates.button("玩家查询", "https://example.com/player")
                    + MailTemplates.small("退订：在网站玩家订阅处取消订阅即可。");
            sendHtml(email, "【修仙榜】玩家订阅成功：" + s.name(),
                    MailTemplates.wrap("玩家变化订阅成功", "👤 玩家订阅成功", body));
            log.info("已发玩家订阅确认邮件 → {}", email);
        } catch (RuntimeException e) {
            log.warn("订阅确认邮件发送失败 {}: {}", email, e.getMessage());
        }
    }

    private void sendChange(String to, String name, java.util.List<String> changes) {
        var body = new StringBuilder();
        body.append(MailTemplates.paragraph(
                "您订阅的玩家「<b>" + MailTemplates.escapeHtml(name) + "</b>」发生变化："));
        body.append("<ul style=\"margin:10px 0;padding-left:22px\">");
        for (String c : changes) {
            body.append("<li style=\"margin:6px 0\">").append(MailTemplates.escapeHtml(c)).append("</li>");
        }
        body.append("</ul>");
        body.append(MailTemplates.button("查看玩家详情", "https://example.com/player"));
        sendHtml(to, "【修仙榜】" + name + " 变化通知",
                MailTemplates.wrap(name + " 变化通知", "🔔 玩家变化通知", body.toString()));
        log.info("已发玩家变化通知 → {}：{} {}", to, name, changes);
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

    private static String realmName(Integer r) {
        if (r == null || r < 0 || r >= REALM.length) {
            return "境界" + (r == null ? "?" : r);
        }
        return REALM[r];
    }

    private static Integer intOrNull(java.sql.ResultSet rs, int idx) throws SQLException {
        int v = rs.getInt(idx);
        return rs.wasNull() ? null : v;
    }
}
