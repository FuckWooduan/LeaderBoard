package com.rankharvester.rank.store;

import com.rankharvester.rank.model.rows.TeamRankRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 战队榜（Team）落地：表 {@code team_rank}，按 {@code leaderboard_code} 整份覆盖（只留最新）。
 *
 * <p>与其他 DuckDB store 共享同一连接，统一以连接对象为监视器串行化访问。
 */
@Repository
public class TeamRankStore {

    private static final Logger log = LoggerFactory.getLogger(TeamRankStore.class);

    private static final String COLS =
            "(leaderboard_code,server_id,snapshot_id,fetched_at,rank,"
            + "team_id,team_name,leader_name,team_lv,team_exp,m_number,m_number_limit,declaration,"
            + "rpg_team_capacity,rpg_team_lv,rpg_team_number,rpg_team_total_bonus,rpg_team_declaration)"
            + " VALUES(" + "?,".repeat(17) + "?)";
    private static final String INSERT = "INSERT INTO team_rank" + COLS;
    private static final String INSERT_STAGING = "INSERT INTO team_rank_staging" + COLS;

    private final Connection conn;

    public TeamRankStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    private static void bind(PreparedStatement ps, TeamRankRow r, String code, int serverId,
            long snapshotId, long fetchedAt) throws SQLException {
        int i = 1;
        ps.setString(i++, code);
        ps.setInt(i++, serverId);
        ps.setLong(i++, snapshotId);
        ps.setLong(i++, fetchedAt);
        ps.setInt(i++, r.rank());
        ps.setObject(i++, r.teamId());
        ps.setObject(i++, r.teamName());
        ps.setObject(i++, r.leaderName());
        ps.setObject(i++, r.teamLv());
        ps.setObject(i++, r.teamExp());
        ps.setObject(i++, r.mNumber());
        ps.setObject(i++, r.mNumberLimit());
        ps.setObject(i++, r.declaration());
        ps.setObject(i++, r.rpgTeamCapacity());
        ps.setObject(i++, r.rpgTeamLv());
        ps.setObject(i++, r.rpgTeamNumber());
        ps.setObject(i++, r.rpgTeamTotalBonus());
        ps.setObject(i, r.rpgTeamDeclaration());
    }

    /** 断点续传：把一页追加进 staging 表（不删、不覆盖），由 promote 在抓满后整体换入正式表。 */
    public void appendStaging(String leaderboardCode, int serverId, long snapshotId, long fetchedAt,
            List<TeamRankRow> rows) {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_STAGING)) {
                for (TeamRankRow r : rows) {
                    bind(ps, r, leaderboardCode, serverId, snapshotId, fetchedAt);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new IllegalStateException("追加 team_rank_staging 失败: " + leaderboardCode, e);
            }
        }
    }

    /** 整份覆盖写入某榜最新快照。 */
    public void writeLatest(String leaderboardCode, int serverId, long snapshotId, long fetchedAt,
            List<TeamRankRow> rows) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement("DELETE FROM team_rank WHERE leaderboard_code = ?")) {
                    del.setString(1, leaderboardCode);
                    del.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
                    for (TeamRankRow r : rows) {
                        bind(ps, r, leaderboardCode, serverId, snapshotId, fetchedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("写入 team_rank 失败: " + leaderboardCode, e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.debug("team_rank 已写入 {} ({} 行)", leaderboardCode, rows.size());
    }

    public long count(String leaderboardCode) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM team_rank WHERE leaderboard_code = ?")) {
                ps.setString(1, leaderboardCode);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计 team_rank 失败", e);
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
