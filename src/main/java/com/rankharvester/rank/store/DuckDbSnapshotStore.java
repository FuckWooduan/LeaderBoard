package com.rankharvester.rank.store;

import com.rankharvester.rank.model.RankKind;
import com.rankharvester.rank.model.RankRow;
import com.rankharvester.rank.model.RankSnapshot;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * DuckDB 快照存储（只留最新一份）。
 *
 * <p>整份覆盖写：{@code writeLatest} 在事务内先删旧行再插新行并 upsert 元信息。
 * 列式 + 字符串去重对重复度高的榜数据压缩友好。单连接访问加锁串行化（DuckDB 单进程语义）。
 */
@Repository
public class DuckDbSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(DuckDbSnapshotStore.class);

    private final Connection conn;
    private final Object lock = new Object();

    public DuckDbSnapshotStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    @PostConstruct
    void init() throws SQLException {
        synchronized (lock) {
            try (var st = conn.createStatement()) {
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS rank_snapshot_meta(
                            leaderboard_code VARCHAR PRIMARY KEY,
                            kind             VARCHAR,
                            snapshot_id      BIGINT,
                            fetched_at       BIGINT,
                            row_count        INTEGER
                        )""");
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS rank_row(
                            leaderboard_code VARCHAR,
                            rank             INTEGER,
                            subject_id       VARCHAR,
                            name             VARCHAR,
                            score            BIGINT,
                            extra_json       VARCHAR
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_rank_row_code ON rank_row(leaderboard_code)");
            }
        }
        log.info("DuckDB 快照表已就绪");
    }

    @Override
    public void writeLatest(RankSnapshot snapshot) {
        synchronized (lock) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement("DELETE FROM rank_row WHERE leaderboard_code = ?")) {
                    del.setString(1, snapshot.leaderboardCode());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO rank_row(leaderboard_code,rank,subject_id,name,score,extra_json) VALUES(?,?,?,?,?,?)")) {
                    for (RankRow r : snapshot.rows()) {
                        ins.setString(1, snapshot.leaderboardCode());
                        ins.setInt(2, r.rank());
                        ins.setString(3, r.subjectId());
                        ins.setString(4, r.name());
                        ins.setLong(5, r.score());
                        ins.setString(6, r.extraJson());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                try (var meta = conn.prepareStatement(
                        """
                        INSERT INTO rank_snapshot_meta(leaderboard_code,kind,snapshot_id,fetched_at,row_count)
                        VALUES(?,?,?,?,?)
                        ON CONFLICT(leaderboard_code) DO UPDATE SET
                            kind=excluded.kind, snapshot_id=excluded.snapshot_id,
                            fetched_at=excluded.fetched_at, row_count=excluded.row_count""")) {
                    meta.setString(1, snapshot.leaderboardCode());
                    meta.setString(2, snapshot.kind().name());
                    meta.setLong(3, snapshot.snapshotId());
                    meta.setLong(4, snapshot.fetchedAtEpochMs());
                    meta.setInt(5, snapshot.rows().size());
                    meta.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("写入快照失败: " + snapshot.leaderboardCode(), e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.debug("已写入快照 {} ({} 行)", snapshot.leaderboardCode(), snapshot.rows().size());
    }

    @Override
    public Optional<RankSnapshot> latestMeta(String leaderboardCode) {
        synchronized (lock) {
            try (var ps = conn.prepareStatement(
                    "SELECT kind,snapshot_id,fetched_at FROM rank_snapshot_meta WHERE leaderboard_code = ?")) {
                ps.setString(1, leaderboardCode);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new RankSnapshot(
                                leaderboardCode,
                                RankKind.valueOf(rs.getString(1)),
                                rs.getLong(2),
                                rs.getLong(3),
                                List.of()));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取快照元信息失败: " + leaderboardCode, e);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<RankRow> page(String leaderboardCode, int offset, int limit) {
        synchronized (lock) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,subject_id,name,score,extra_json FROM rank_row WHERE leaderboard_code = ? ORDER BY rank LIMIT ? OFFSET ?")) {
                ps.setString(1, leaderboardCode);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                return readRows(ps);
            } catch (SQLException e) {
                throw new IllegalStateException("分页读取失败: " + leaderboardCode, e);
            }
        }
    }

    @Override
    public List<RankRow> findByName(String leaderboardCode, String name, int limit) {
        synchronized (lock) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,subject_id,name,score,extra_json FROM rank_row WHERE leaderboard_code = ? AND name LIKE ? ORDER BY rank LIMIT ?")) {
                ps.setString(1, leaderboardCode);
                ps.setString(2, "%" + name + "%");
                ps.setInt(3, limit);
                return readRows(ps);
            } catch (SQLException e) {
                throw new IllegalStateException("按名查询失败: " + leaderboardCode, e);
            }
        }
    }

    private List<RankRow> readRows(PreparedStatement ps) throws SQLException {
        var out = new ArrayList<RankRow>();
        try (var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new RankRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)));
            }
        }
        return out;
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
