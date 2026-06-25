package com.rankharvester.rank.store;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 留言板持久层。
 *
 * <p>表 {@code guest_message}：游客留言 + 站长回复 + 审核状态（隐藏/置顶）。
 * 使用<b>第三条 DuckDB 连接</b>（duckDbMetaConnection，管理/元数据专用）——留言读写是低频小写，
 * 不与分区抓取（第二连接）的大批量写入抢锁，避免抓取忙时留言板卡死。
 */
@Repository
public class MessageBoardStore {

    private static final Logger log = LoggerFactory.getLogger(MessageBoardStore.class);

    private final Connection conn;

    public MessageBoardStore(@Qualifier("duckDbMetaConnection") Connection conn) {
        this.conn = conn;
    }

    @PostConstruct
    public void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SEQUENCE IF NOT EXISTS guest_message_id_seq");
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS guest_message(
                            id            BIGINT PRIMARY KEY,
                            nickname      VARCHAR,
                            content       VARCHAR,
                            ip            VARCHAR,
                            created_at    BIGINT,
                            visible       BOOLEAN,
                            pinned        BOOLEAN,
                            reply_text    VARCHAR,
                            reply_at      BIGINT,
                            email         VARCHAR,
                            review_status VARCHAR,
                            review_reason VARCHAR,
                            reviewed_at   BIGINT
                        )""");
                // 老表迁移（首版无审核字段）
                st.execute("ALTER TABLE guest_message ADD COLUMN IF NOT EXISTS email VARCHAR");
                st.execute("ALTER TABLE guest_message ADD COLUMN IF NOT EXISTS review_status VARCHAR");
                st.execute("ALTER TABLE guest_message ADD COLUMN IF NOT EXISTS review_reason VARCHAR");
                st.execute("ALTER TABLE guest_message ADD COLUMN IF NOT EXISTS reviewed_at BIGINT");
                // 刷屏治理（每小时 AI 整理）：本条吸收了多少条相似留言（>0 时前台显示「已合并 N 条」）
                st.execute("ALTER TABLE guest_message ADD COLUMN IF NOT EXISTS merged_count INTEGER DEFAULT 0");
            }
        }
        log.info("DuckDB guest_message 表已就绪");
    }

    /**
     * 单条留言（ip / email / 审核字段仅后台可见，公开接口不下发）。
     * reviewStatus：PENDING/PASS/REJECTED/SKIPPED/MERGED（被整理任务合并进别的留言后隐藏）。
     * mergedCount：本条吸收的相似留言数（>0 时前台显示「已合并 N 条」）。
     */
    public record Message(long id, String nickname, String content, String ip, long createdAt,
                          boolean visible, boolean pinned, String replyText, long replyAt,
                          String email, String reviewStatus, String reviewReason, long reviewedAt,
                          int mergedCount) {}

    /** 一页留言 + 总数。 */
    public record Page(List<Message> messages, long total) {}

    /** 新增留言（默认可见、不置顶、审核状态 PENDING=先显示后审核），返回新留言 id。 */
    public long add(String nickname, String content, String ip, String email, long createdAt) {
        synchronized (conn) {
            try {
                long id;
                try (var ps = conn.prepareStatement("SELECT nextval('guest_message_id_seq')");
                        var rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
                try (var ps = conn.prepareStatement(
                        """
                        INSERT INTO guest_message(id, nickname, content, ip, created_at, visible, pinned, reply_text, reply_at,
                                                  email, review_status, review_reason, reviewed_at)
                        VALUES(?,?,?,?,?,TRUE,FALSE,NULL,0,?,'PENDING',NULL,0)""")) {
                    ps.setLong(1, id);
                    ps.setString(2, nickname);
                    ps.setString(3, content);
                    ps.setString(4, ip);
                    ps.setLong(5, createdAt);
                    ps.setString(6, email);
                    ps.executeUpdate();
                }
                return id;
            } catch (SQLException e) {
                throw new IllegalStateException("写入 guest_message 失败", e);
            }
        }
    }

    /** 前台分页：仅可见，置顶优先，其余新→旧。 */
    public Page listPublic(int page, int size) {
        return list("WHERE visible", page, size);
    }

    /** 后台分页：全部（含隐藏），置顶优先，其余新→旧。 */
    public Page listAdmin(int page, int size) {
        return list("", page, size);
    }

    private Page list(String where, int page, int size) {
        synchronized (conn) {
            try {
                long total;
                try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM guest_message " + where);
                        var rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
                var out = new ArrayList<Message>();
                try (var ps = conn.prepareStatement(
                        "SELECT id, nickname, content, ip, created_at, visible, pinned, reply_text, reply_at,"
                                + " email, review_status, review_reason, reviewed_at, merged_count"
                                + " FROM guest_message " + where
                                + " ORDER BY pinned DESC, id DESC LIMIT ? OFFSET ?")) {
                    ps.setInt(1, size);
                    ps.setInt(2, Math.max(0, (page - 1) * size));
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readMessage(rs));
                        }
                    }
                }
                return new Page(out, total);
            } catch (SQLException e) {
                throw new IllegalStateException("读取 guest_message 失败", e);
            }
        }
    }

    /** 显示/隐藏。@return 是否命中记录 */
    public boolean setVisible(long id, boolean visible) {
        return exec("UPDATE guest_message SET visible=? WHERE id=?", ps -> {
            ps.setBoolean(1, visible);
            ps.setLong(2, id);
        });
    }

    /** 置顶/取消置顶。@return 是否命中记录 */
    public boolean setPinned(long id, boolean pinned) {
        return exec("UPDATE guest_message SET pinned=? WHERE id=?", ps -> {
            ps.setBoolean(1, pinned);
            ps.setLong(2, id);
        });
    }

    /** 站长回复（text 为空=清除回复）。@return 是否命中记录 */
    public boolean reply(long id, String text, long repliedAt) {
        boolean clear = text == null || text.isBlank();
        return exec("UPDATE guest_message SET reply_text=?, reply_at=? WHERE id=?", ps -> {
            if (clear) {
                ps.setNull(1, java.sql.Types.VARCHAR);
                ps.setLong(2, 0L);
            } else {
                ps.setString(1, text);
                ps.setLong(2, repliedAt);
            }
            ps.setLong(3, id);
        });
    }

    /** 物理删除。@return 是否命中记录 */
    public boolean delete(long id) {
        return exec("DELETE FROM guest_message WHERE id=?", ps -> ps.setLong(1, id));
    }

    /** AI 审核通过/跳过：只更新审核字段，保持可见。 */
    public boolean markReview(long id, String status, String reason, long reviewedAt) {
        return exec("UPDATE guest_message SET review_status=?, review_reason=?, reviewed_at=? WHERE id=?", ps -> {
            ps.setString(1, status);
            if (reason == null || reason.isBlank()) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, reason);
            }
            ps.setLong(3, reviewedAt);
            ps.setLong(4, id);
        });
    }

    /** AI 审核拒绝：下架（visible=false）并记录原因。 */
    public boolean rejectByReview(long id, String reason, long reviewedAt) {
        return exec(
                "UPDATE guest_message SET visible=FALSE, review_status='REJECTED', review_reason=?, reviewed_at=? WHERE id=?",
                ps -> {
                    ps.setString(1, reason == null ? "" : reason);
                    ps.setLong(2, reviewedAt);
                    ps.setLong(3, id);
                });
    }

    private static MessageBoardStore.Message readMessage(java.sql.ResultSet rs) throws SQLException {
        return new Message(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getBoolean(6),
                rs.getBoolean(7),
                rs.getString(8),
                rs.getLong(9),
                rs.getString(10),
                rs.getString(11),
                rs.getString(12),
                rs.getLong(13),
                rs.getInt(14));
    }

    // ── 刷屏治理（每小时 AI 整理）────────────────────────────────────────────────

    /** 最新 n 条「可见」留言（新→旧，不含置顶权重——整理任务按时间序看刷屏）。 */
    public List<Message> listLatestVisible(int n) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT id, nickname, content, ip, created_at, visible, pinned, reply_text, reply_at,"
                            + " email, review_status, review_reason, reviewed_at, merged_count"
                            + " FROM guest_message WHERE visible ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, n);
                try (var rs = ps.executeQuery()) {
                    var out = new ArrayList<Message>();
                    while (rs.next()) {
                        out.add(readMessage(rs));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取最新 guest_message 失败", e);
            }
        }
    }

    /** 合并：保留条更新内容（null=不改写）并累加吸收数。 */
    public boolean applyMergeKeep(long keepId, String mergedContent, int absorbed) {
        return exec(
                "UPDATE guest_message SET content=COALESCE(?, content), merged_count=merged_count+? WHERE id=?",
                ps -> {
                    if (mergedContent == null || mergedContent.isBlank()) {
                        ps.setNull(1, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(1, mergedContent);
                    }
                    ps.setInt(2, absorbed);
                    ps.setLong(3, keepId);
                });
    }

    /** 合并：被吸收条下架并标记 MERGED（保留原文供后台追溯）。 */
    public boolean applyMergeAbsorb(long id, long keepId, String reason, long now) {
        return exec(
                "UPDATE guest_message SET visible=FALSE, review_status='MERGED', review_reason=?, reviewed_at=?"
                        + " WHERE id=? AND visible",
                ps -> {
                    ps.setString(1, "合并入 #" + keepId + (reason == null || reason.isBlank() ? "" : "：" + reason));
                    ps.setLong(2, now);
                    ps.setLong(3, id);
                });
    }

    /** 该 IP 自 since 起发了多少条（限流兜底，Redis 不可用时仍能挡刷屏）。 */
    public long countByIpSince(String ip, long since) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM guest_message WHERE ip=? AND created_at>=?")) {
                ps.setString(1, ip);
                ps.setLong(2, since);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计 guest_message 失败", e);
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    private boolean exec(String sql, Binder binder) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("更新 guest_message 失败", e);
            }
        }
    }
}
