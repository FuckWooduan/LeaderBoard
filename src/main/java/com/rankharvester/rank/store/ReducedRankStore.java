package com.rankharvester.rank.store;

import com.rankharvester.rank.model.rows.ReducedRankRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 战力/经验榜（Reduced）落地：表 {@code reduced_rank}（无主键，靠 snapshot_id 区分批次）。
 *
 * <p><b>增量批量 append</b>：分页每抓一页即批量 INSERT（DuckDB 批量插入毫秒级，避免逐行 ON CONFLICT
 * 长时间独占连接把读饿死），数据随抓随可见。同一玩家在「上一份已抓满快照」与「本轮在抓快照」可能各有一行，
 * <b>读路径按 score_gc_id 取 snapshot_id 最大者去重</b>；抓满后 {@link #prune} 删掉旧快照行。
 * 排名改由读路径按后台配置字段排序，不依赖抓包顺序，故一次读多少写多少。
 *
 * <p>与其他 DuckDB store 共享同一连接，统一以连接对象为监视器串行化访问。
 */
@Repository
public class ReducedRankStore {

    private static final Logger log = LoggerFactory.getLogger(ReducedRankStore.class);

    private static final String COLS =
            "(leaderboard_code,server_id,snapshot_id,fetched_at,rank,rank_type,"
            + "last_rank,dateline,update_display_count,rank_id,display,unique_id_bean,last_change_display_flag,"
            + "info_team_name,info_blue_vip_type,info_login_id,info_douwa_vip,info_homeland_open,info_char_id,"
            + "info_lv,info_char_name,info_operator_id,info_blue_vip_level,"
            + "score_all_number,score_number,score_dateline,score_gc_id,score_rebirth)"
            + " VALUES(" + "?,".repeat(27) + "?)";
    private static final String INSERT = "INSERT INTO reduced_rank" + COLS;

    private final Connection conn;

    public ReducedRankStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    private static void bind(PreparedStatement ps, ReducedRankRow r, String code, int serverId,
            long snapshotId, long fetchedAt) throws SQLException {
        int i = 1;
        ps.setString(i++, code);
        ps.setInt(i++, serverId);
        ps.setLong(i++, snapshotId);
        ps.setLong(i++, fetchedAt);
        ps.setInt(i++, r.rank());
        ps.setInt(i++, r.rankType());
        ps.setObject(i++, r.lastRank());
        ps.setObject(i++, r.dateline());
        ps.setObject(i++, r.updateDisplayCount());
        ps.setObject(i++, r.rankId());
        ps.setObject(i++, r.display());
        ps.setObject(i++, r.uniqueIdBean());
        ps.setObject(i++, r.lastChangeDisplayFlag());
        ps.setObject(i++, r.infoTeamName());
        ps.setObject(i++, r.infoBlueVipType());
        ps.setObject(i++, r.infoLoginId());
        ps.setObject(i++, r.infoDouwaVip());
        ps.setObject(i++, r.infoHomelandOpen());
        ps.setObject(i++, r.infoCharId());
        ps.setObject(i++, r.infoLv());
        ps.setObject(i++, r.infoCharName());
        ps.setObject(i++, r.infoOperatorId());
        ps.setObject(i++, r.infoBlueVipLevel());
        ps.setObject(i++, r.scoreAllNumber());
        ps.setObject(i++, r.scoreNumber());
        ps.setObject(i++, r.scoreDateline());
        ps.setObject(i++, r.scoreGcId());
        ps.setObject(i, r.scoreRebirth());
    }

    /**
     * 增量批量 append 一页：直接 INSERT 本页行（带本轮 snapshotId）。DuckDB 批量插入很快，不长时间占用连接。
     * 同一 snapshot 内每页只抓一次（断点续传按 next_offset 续抓，不回退重抓），故同快照内不会重复；
     * 跨快照的重复（旧快照同一玩家）由读路径取最新 snapshot 去重、抓满后 {@link #prune} 清理。
     */
    public void appendPage(String leaderboardCode, int serverId, long snapshotId, long fetchedAt,
            List<ReducedRankRow> rows) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
                    for (ReducedRankRow r : rows) {
                        bind(ps, r, leaderboardCode, serverId, snapshotId, fetchedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("append reduced_rank 失败: " + leaderboardCode, e);
            } finally {
                setAutoCommitQuietly();
            }
        }
    }

    /** 抓满后清掉某榜「非本轮快照」的陈旧行（掉榜玩家等）。本轮所有页共用 keepSnapshotId。 */
    public void prune(String leaderboardCode, long keepSnapshotId) {
        synchronized (conn) {
            try (var del = conn.prepareStatement(
                    "DELETE FROM reduced_rank WHERE leaderboard_code = ? AND snapshot_id <> ?")) {
                del.setString(1, leaderboardCode);
                del.setLong(2, keepSnapshotId);
                int n = del.executeUpdate();
                if (n > 0) log.debug("reduced_rank 清理陈旧行 {} ({} 行)", leaderboardCode, n);
            } catch (SQLException e) {
                throw new IllegalStateException("清理 reduced_rank 陈旧行失败: " + leaderboardCode, e);
            }
        }
    }

    /** 实际玩家数（按 score_gc_id 去重，避免新旧快照并存时翻倍）。 */
    public long count(String leaderboardCode) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(DISTINCT score_gc_id) FROM reduced_rank WHERE leaderboard_code = ?")) {
                ps.setString(1, leaderboardCode);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计 reduced_rank 失败", e);
            }
        }
    }

    /**
     * 按 gcId 批量查战力：gcId → [战力 score_number, VIP score_all_number, 境界 score_rebirth]。
     * <b>优先同服战力(382)</b>；同服查不到的（如该大区 382 榜还没抓到），<b>兜底用跨服战力(333)</b>。
     * 按 score_gc_id 取最新快照去重。用于给分区榜补玩家战力/VIP/境界。
     */
    public java.util.Map<Long, long[]> powerStatsByGcId(java.util.Collection<Long> gcIds) {
        var out = new java.util.HashMap<Long, long[]>();
        if (gcIds == null || gcIds.isEmpty()) {
            return out;
        }
        var ids = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(gcIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) {
            return out;
        }
        queryPower(ids, "REDUCED:382:%", out); // 同服战力优先
        // 同服没查到的 gcId，兜底用跨服战力(333，全服一张榜)
        var missing = new java.util.ArrayList<Long>();
        for (Long id : ids) {
            if (!out.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            queryPower(missing, "REDUCED:333:%", out);
        }
        return out;
    }

    /**
     * 当前经验榜（同服 331 + 跨服 332，最新快照）里每个玩家的 [gc_id, 经验 score_number, 等级 info_lv]。
     * 给 player_momentum 每小时记动量用（经验/等级变化）。按 gc_id 取最新快照去重。
     */
    public java.util.List<long[]> latestExpLvByGcId() {
        var out = new java.util.ArrayList<long[]>();
        String sql = "SELECT gc_id, expv, lvv FROM ("
                + "SELECT score_gc_id AS gc_id, score_number AS expv, info_lv AS lvv,"
                + " row_number() OVER (PARTITION BY score_gc_id ORDER BY snapshot_id DESC) AS rn"
                + " FROM reduced_rank"
                + " WHERE (leaderboard_code LIKE 'REDUCED:331:%' OR leaderboard_code LIKE 'REDUCED:332:%')"
                + " AND score_gc_id IS NOT NULL) WHERE rn = 1";
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long gc = rs.getLong(1);
                    if (gc == 0) {
                        continue;
                    }
                    out.add(new long[] {gc, rs.getLong(2), rs.getLong(3)});
                }
            } catch (SQLException e) {
                log.warn("读经验榜动量数据失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 在 {@code codeLike} 匹配的榜里按 gcId 查战力/VIP/境界，写入 out（已存在的 key 不覆盖）。 */
    private void queryPower(java.util.List<Long> ids, String codeLike, java.util.Map<Long, long[]> out) {
        if (ids.isEmpty()) {
            return;
        }
        String ph = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT score_gc_id, score_number, score_all_number, score_rebirth FROM ("
                + "SELECT score_gc_id, score_number, score_all_number, score_rebirth,"
                + " row_number() OVER (PARTITION BY score_gc_id ORDER BY snapshot_id DESC) AS rn"
                + " FROM reduced_rank WHERE leaderboard_code LIKE ? AND score_gc_id IN (" + ph + ")"
                + ") WHERE rn = 1";
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, codeLike);
                for (Long id : ids) {
                    ps.setLong(i++, id);
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.putIfAbsent(rs.getLong(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                    }
                }
            } catch (SQLException e) {
                log.warn("按 gcId 批量查战力({})失败: {}", codeLike, e.getMessage());
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
