package com.rankharvester.rank.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 断点续传支撑：检查点（{@code rank_progress}）读写 + staging 影子表的清理与 promote。
 *
 * <p>分页抓取时逐页 append 到 {@code <table>_staging}，并在 {@link #saveProgress} 记录下一页 offset；
 * 抓满后 {@link #promote} 把 staging 整体原子换入正式表（正式表始终只持有「上一份完整快照」，读路径不受影响）。
 * 中途失败则检查点与 staging 都保留，重抓时从 {@code next_offset} 续传。
 */
@Repository
public class RankResumeStore {

    private static final Logger log = LoggerFactory.getLogger(RankResumeStore.class);

    /** 允许 promote/清理的正式表白名单（表名直接拼 SQL，必须白名单约束）。 */
    private static final Set<String> ALLOWED_TABLES = Set.of("reduced_rank", "team_rank", "ladder_rank");

    private final Connection conn;

    public RankResumeStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    /** 抓取进度检查点。 */
    public record Progress(long snapshotId, int nextOffset, Integer total) {}

    public Optional<Progress> loadProgress(String code) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT snapshot_id, next_offset, total FROM rank_progress WHERE leaderboard_code = ?")) {
                ps.setString(1, code);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    long snap = rs.getLong(1);
                    int off = rs.getInt(2);
                    int total = rs.getInt(3);
                    Integer totalOrNull = rs.wasNull() ? null : total;
                    return Optional.of(new Progress(snap, off, totalOrNull));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 rank_progress 失败: " + code, e);
            }
        }
    }

    public void saveProgress(String code, long snapshotId, int nextOffset, Integer total, long nowMs) {
        synchronized (conn) {
            try {
                try (var del = conn.prepareStatement("DELETE FROM rank_progress WHERE leaderboard_code = ?")) {
                    del.setString(1, code);
                    del.executeUpdate();
                }
                try (var ins = conn.prepareStatement(
                        "INSERT INTO rank_progress(leaderboard_code,snapshot_id,next_offset,total,updated_at)"
                        + " VALUES(?,?,?,?,?)")) {
                    ins.setString(1, code);
                    ins.setLong(2, snapshotId);
                    ins.setInt(3, nextOffset);
                    if (total == null) {
                        ins.setNull(4, java.sql.Types.INTEGER);
                    } else {
                        ins.setInt(4, total);
                    }
                    ins.setLong(5, nowMs);
                    ins.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("写入 rank_progress 失败: " + code, e);
            }
        }
    }

    public void clearProgress(String code) {
        synchronized (conn) {
            try (var del = conn.prepareStatement("DELETE FROM rank_progress WHERE leaderboard_code = ?")) {
                del.setString(1, code);
                del.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清除 rank_progress 失败: " + code, e);
            }
        }
    }

    /** 清空某榜某 snapshot 在 staging 的残留（仅清本次快照，不影响并发的其它抓取）。 */
    public void clearStaging(String realTable, String code, long snapshotId) {
        String staging = staging(realTable);
        synchronized (conn) {
            try (var del = conn.prepareStatement(
                    "DELETE FROM " + staging + " WHERE leaderboard_code = ? AND snapshot_id = ?")) {
                del.setString(1, code);
                del.setLong(2, snapshotId);
                del.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清空 " + staging + " 失败: " + code, e);
            }
        }
    }

    /**
     * 抓满后原子换表：正式表删旧 → 从 staging 灌入<b>本 snapshot</b> → 清本 snapshot 的 staging。
     * 按 snapshot_id 隔离，使同一 code 的并发抓取互不串行（避免重复行）。
     */
    public void promote(String realTable, String code, long snapshotId) {
        String staging = staging(realTable);
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement("DELETE FROM " + realTable + " WHERE leaderboard_code = ?")) {
                    del.setString(1, code);
                    del.executeUpdate();
                }
                try (var ins = conn.prepareStatement(
                        "INSERT INTO " + realTable + " SELECT * FROM " + staging
                                + " WHERE leaderboard_code = ? AND snapshot_id = ?")) {
                    ins.setString(1, code);
                    ins.setLong(2, snapshotId);
                    ins.executeUpdate();
                }
                try (var clr = conn.prepareStatement(
                        "DELETE FROM " + staging + " WHERE leaderboard_code = ? AND snapshot_id = ?")) {
                    clr.setString(1, code);
                    clr.setLong(2, snapshotId);
                    clr.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("promote " + staging + " → " + realTable + " 失败: " + code, e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.debug("{} 已 promote（{} snapshot={}）", realTable, code, snapshotId);
    }

    private static String staging(String realTable) {
        if (!ALLOWED_TABLES.contains(realTable)) {
            throw new IllegalArgumentException("非法正式表名: " + realTable);
        }
        return realTable + "_staging";
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // best-effort
        }
    }

    private void setAutoCommitQuietly() {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignore) {
            // best-effort
        }
    }
}
