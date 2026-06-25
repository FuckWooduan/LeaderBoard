package com.rankharvester.rank.store;

import com.rankharvester.rank.model.rows.LadderRankRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 天梯联赛榜（Ladder）落地：表 {@code ladder_rank}，按 {@code leaderboard_code} 整份覆盖（只留最新）。
 */
@Repository
public class LadderRankStore {

    private static final Logger log = LoggerFactory.getLogger(LadderRankStore.class);

    private static final String COLS =
            "(leaderboard_code,server_id,snapshot_id,fetched_at,rank,"
            + "team_name,last_rank,medal_index,login_id,char_id,lv,user_name,char_name,"
            + "extra_value,medal_line,cur_rank,number,team_id,operator_id)"
            + " VALUES(" + "?,".repeat(18) + "?)";
    private static final String INSERT = "INSERT INTO ladder_rank" + COLS;
    private static final String INSERT_STAGING = "INSERT INTO ladder_rank_staging" + COLS;

    private final Connection conn;

    public LadderRankStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    private static void bind(PreparedStatement ps, LadderRankRow r, String code, int serverId,
            long snapshotId, long fetchedAt) throws SQLException {
        int i = 1;
        ps.setString(i++, code);
        ps.setInt(i++, serverId);
        ps.setLong(i++, snapshotId);
        ps.setLong(i++, fetchedAt);
        ps.setInt(i++, r.rank());
        ps.setObject(i++, r.teamName());
        ps.setObject(i++, r.lastRank());
        ps.setObject(i++, r.medalIndex());
        ps.setObject(i++, r.loginId());
        ps.setObject(i++, r.charId());
        ps.setObject(i++, r.lv());
        ps.setObject(i++, r.userName());
        ps.setObject(i++, r.charName());
        ps.setObject(i++, r.extraValue());
        ps.setObject(i++, r.medalLine());
        ps.setObject(i++, r.curRank());
        ps.setObject(i++, r.number());
        ps.setObject(i++, r.teamId());
        ps.setObject(i, r.operatorId());
    }

    /** 断点续传：把一页追加进 staging 表（不删、不覆盖），由 promote 在抓满后整体换入正式表。 */
    public void appendStaging(String leaderboardCode, int serverId, long snapshotId, long fetchedAt,
            List<LadderRankRow> rows) {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_STAGING)) {
                for (LadderRankRow r : rows) {
                    bind(ps, r, leaderboardCode, serverId, snapshotId, fetchedAt);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new IllegalStateException("追加 ladder_rank_staging 失败: " + leaderboardCode, e);
            }
        }
    }

    public void writeLatest(String leaderboardCode, int serverId, long snapshotId, long fetchedAt,
            List<LadderRankRow> rows) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement("DELETE FROM ladder_rank WHERE leaderboard_code = ?")) {
                    del.setString(1, leaderboardCode);
                    del.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
                    for (LadderRankRow r : rows) {
                        bind(ps, r, leaderboardCode, serverId, snapshotId, fetchedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("写入 ladder_rank 失败: " + leaderboardCode, e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.debug("ladder_rank 已写入 {} ({} 行)", leaderboardCode, rows.size());
    }

    public long count(String leaderboardCode) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM ladder_rank WHERE leaderboard_code = ?")) {
                ps.setString(1, leaderboardCode);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计 ladder_rank 失败", e);
            }
        }
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
