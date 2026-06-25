package com.rankharvester.rank.slice;

import com.rankharvester.rank.model.rows.ReducedRankRow;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 分区排行榜<b>数据</b>落地（{@code getReducedRankByPage} 抓到的每个分区的榜行）。
 *
 * <p>一张通用表 {@code partition_rank}（rank_key + partition + 行）。复用 {@link ReducedRankRow}
 * 的通用字段（角色名/角色id/区/等级/战队/分数），渲染方式与跨服战力一致。走分区获取专用第二连接，
 * 与主连接 MVCC 并发、互不抢锁。每个 (rankKey, 分区) 重抓时整段替换。
 */
@Repository
public class PartitionRankStore {

    private static final Logger log = LoggerFactory.getLogger(PartitionRankStore.class);

    private final Connection conn;

    public PartitionRankStore(@Qualifier("duckDbSliceConnection") Connection duckDbSliceConnection) {
        this.conn = duckDbSliceConnection;
    }

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS partition_rank(
                            rank_key      VARCHAR,
                            partition_idx INTEGER,
                            rank          INTEGER,
                            char_name     VARCHAR,
                            char_id       BIGINT,
                            login_id      BIGINT,
                            lv            INTEGER,
                            team_name     VARCHAR,
                            score_number  BIGINT,
                            score_extra   BIGINT,
                            gc_id         BIGINT,
                            last_rank     BIGINT,
                            display       BOOLEAN,
                            last_change   BIGINT,
                            dateline      BIGINT,
                            updated_at    BIGINT
                        )""");
            } catch (SQLException e) {
                throw new IllegalStateException("建 partition_rank 表失败", e);
            }
            // 迁移：老表补列。dateline=游戏侧该行更新时间(真实，前台「更新时间」用它；lastChangeDisplayFlag 恒为 0 不可用)。
            for (String col : new String[] {"last_rank BIGINT", "display BOOLEAN", "last_change BIGINT", "dateline BIGINT"}) {
                try (var st = conn.createStatement()) {
                    st.execute("ALTER TABLE partition_rank ADD COLUMN IF NOT EXISTS " + col);
                } catch (SQLException ignore) { /* 已存在 */ }
            }
        }
        log.info("partition_rank 表就绪（{} 行）", count());
    }

    /** 整段替换某 (rankKey, 分区) 的榜行（DELETE 该段 + 批量 INSERT）。 */
    public void replacePartition(String rankKey, int partition, List<ReducedRankRow> rows, long now) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement(
                        "DELETE FROM partition_rank WHERE rank_key=? AND partition_idx=?")) {
                    del.setString(1, rankKey);
                    del.setInt(2, partition);
                    del.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO partition_rank(rank_key,partition_idx,rank,char_name,char_id,login_id,"
                        + "lv,team_name,score_number,score_extra,gc_id,last_rank,display,last_change,dateline,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (ReducedRankRow r : rows) {
                        ps.setString(1, rankKey);
                        ps.setInt(2, partition);
                        ps.setInt(3, r.rank());
                        ps.setString(4, r.infoCharName());
                        setLong(ps, 5, r.infoCharId());
                        setLong(ps, 6, r.infoLoginId());
                        setInt(ps, 7, r.infoLv());
                        ps.setString(8, r.infoTeamName());
                        setLong(ps, 9, r.scoreNumber());
                        setLong(ps, 10, r.scoreAllNumber());
                        setLong(ps, 11, r.scoreGcId());
                        setLong(ps, 12, r.lastRank());
                        if (r.display() == null) {
                            ps.setNull(13, java.sql.Types.BOOLEAN);
                        } else {
                            ps.setBoolean(13, r.display());
                        }
                        setLong(ps, 14, r.lastChangeDisplayFlag()); // 恒为 0，保留兼容
                        setLong(ps, 15, r.dateline());              // 游戏侧该行更新时间(真实)
                        ps.setLong(16, now);                        // 我方抓取时间
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("写 partition_rank 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
    }

    /** 单分区分页（按 rank 升序）。 */
    public List<Map<String, Object>> page(String rankKey, int partition, int page, int size) {
        return query("SELECT rank,char_name,login_id,lv,team_name,score_number,partition_idx,gc_id,last_rank,display,last_change,dateline,updated_at"
                + " FROM partition_rank WHERE rank_key=? AND partition_idx=? ORDER BY rank LIMIT ? OFFSET ?",
                rankKey, partition, size, (Math.max(1, page) - 1) * size);
    }

    /** 取某 (rankKey, 分区) 里指定名次的那一行（隐藏玩家身份预测的递归查找用）。无则空 list。 */
    public List<Map<String, Object>> rowAt(String rankKey, int partition, int rank) {
        var out = new ArrayList<Map<String, Object>>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,char_name,login_id,lv,team_name,score_number,partition_idx,gc_id,last_rank,display,last_change,dateline,updated_at"
                    + " FROM partition_rank WHERE rank_key=? AND partition_idx=? AND rank=? LIMIT 1")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                ps.setInt(3, rank);
                try (var rs = ps.executeQuery()) {
                    var md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    while (rs.next()) {
                        var row = new java.util.LinkedHashMap<String, Object>();
                        for (int c = 1; c <= cols; c++) {
                            row.put(md.getColumnLabel(c), rs.getObject(c));
                        }
                        out.add(row);
                    }
                }
            } catch (SQLException e) {
                log.warn("rowAt 查 partition_rank 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 取某玩家(gc_id)在某 rankKey 的名次（全榜按 gc_id 查；隐藏玩家身份匹配用：候选的上赛季名次）。无则 null。 */
    public Integer rankByGcId(String rankKey, long gcId) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank FROM partition_rank WHERE rank_key=? AND gc_id=? AND char_name IS NOT NULL LIMIT 1")) {
                ps.setString(1, rankKey);
                ps.setLong(2, gcId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int v = rs.getInt(1);
                        return rs.wasNull() ? null : v;
                    }
                }
            } catch (SQLException e) {
                log.warn("rankByGcId 查 partition_rank 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /** 取某玩家(gc_id)在某 rankKey 的分数（全榜按 gc_id 查；同一玩家在不同榜分区可能不同，故不限分区）。无则 null。 */
    public Long scoreByGcId(String rankKey, long gcId) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT score_number FROM partition_rank WHERE rank_key=? AND gc_id=? LIMIT 1")) {
                ps.setString(1, rankKey);
                ps.setLong(2, gcId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? null : v;
                    }
                }
            } catch (SQLException e) {
                log.warn("scoreByGcId 查 partition_rank 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /** 某 (rankKey, 分区) 的全部命名玩家（char_name 非空），含 gc_id/分数（隐藏玩家 AI 预测的候选集用）。 */
    public List<Map<String, Object>> namedRows(String rankKey, int partition) {
        var out = new ArrayList<Map<String, Object>>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,char_name,gc_id,score_number,login_id,lv,team_name FROM partition_rank"
                    + " WHERE rank_key=? AND partition_idx=? AND char_name IS NOT NULL AND char_name <> '' ORDER BY rank")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                try (var rs = ps.executeQuery()) {
                    var md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    while (rs.next()) {
                        var row = new java.util.LinkedHashMap<String, Object>();
                        for (int c = 1; c <= cols; c++) {
                            row.put(md.getColumnLabel(c), rs.getObject(c));
                        }
                        out.add(row);
                    }
                }
            } catch (SQLException e) {
                log.warn("namedRows 查 partition_rank 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 全部分区汇总（跨分区，按分数降序），含 partition 列。 */
    public List<Map<String, Object>> pageAll(String rankKey, int page, int size) {
        return query("SELECT rank,char_name,login_id,lv,team_name,score_number,partition_idx,gc_id,last_rank,display,last_change,dateline,updated_at"
                + " FROM partition_rank WHERE rank_key=? ORDER BY score_number DESC LIMIT ? OFFSET ?",
                rankKey, null, size, (Math.max(1, page) - 1) * size);
    }

    /**
     * 龙虎榜：各分区第 1 名 → 各分区第 2 名 → …（先按分区内名次升序，同一名次内按分数降序）。
     * 保留分区内名次 rank（各分区第 1 名都显示 1），含 partition 列。
     */
    public List<Map<String, Object>> pageDragon(String rankKey, int page, int size) {
        return query("SELECT rank,char_name,login_id,lv,team_name,score_number,partition_idx,gc_id,last_rank,display,last_change,dateline,updated_at"
                + " FROM partition_rank WHERE rank_key=? ORDER BY rank ASC, score_number DESC LIMIT ? OFFSET ?",
                rankKey, null, size, (Math.max(1, page) - 1) * size);
    }

    public long countOf(String rankKey, Integer partition) {
        synchronized (conn) {
            String sql = "SELECT COUNT(*) FROM partition_rank WHERE rank_key=?"
                    + (partition != null ? " AND partition_idx=?" : "");
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, rankKey);
                if (partition != null) {
                    ps.setInt(2, partition);
                }
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 该 (rankKey, 分区) 的数据是否在 ts 之后刷新过（季末抢数：判断本轮是否已抓到该分区）。 */
    public boolean freshAfter(String rankKey, int partition, long ts) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT MAX(updated_at) FROM partition_rank WHERE rank_key=? AND partition_idx=?")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        return !rs.wasNull() && v >= ts;
                    }
                }
            } catch (SQLException e) {
                return false;
            }
        }
        return false;
    }

    /** 某 rankKey 下「在 ts 之后刷新过」的分区集合（季末完整性校验：一次查整榜，避免逐分区查）。 */
    public java.util.Set<Integer> freshPartitions(String rankKey, long ts) {
        var out = new java.util.HashSet<Integer>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT partition_idx FROM partition_rank WHERE rank_key=?"
                    + " GROUP BY partition_idx HAVING MAX(updated_at) >= ?")) {
                ps.setString(1, rankKey);
                ps.setLong(2, ts);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getInt(1));
                    }
                }
            } catch (SQLException e) {
                log.warn("freshPartitions 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 按角色名<b>模糊</b>(ILIKE 子串)匹配，返回匹配玩家在所有 rankKey/分区的名次+分数（跨 rankKey 全区查询）。 */
    public List<Map<String, Object>> playerAcross(String charName) {
        return query("SELECT char_name,rank_key,partition_idx,rank,score_number,login_id,team_name,lv"
                + " FROM partition_rank WHERE char_name ILIKE ? ORDER BY char_name, rank_key, partition_idx LIMIT ? OFFSET ?",
                "%" + charName + "%", null, 1000, 0);
    }

    private List<Map<String, Object>> query(String sql, String rankKey, Integer partition, int limit, int offset) {
        var out = new ArrayList<Map<String, Object>>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, rankKey);
                if (partition != null && sql.contains("partition_idx=?")) {
                    ps.setInt(i++, partition);
                }
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
                try (var rs = ps.executeQuery()) {
                    var md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    while (rs.next()) {
                        var row = new java.util.LinkedHashMap<String, Object>();
                        for (int c = 1; c <= cols; c++) {
                            row.put(md.getColumnLabel(c), rs.getObject(c));
                        }
                        out.add(row);
                    }
                }
            } catch (SQLException e) {
                log.warn("查 partition_rank 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM partition_rank")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 清空整张分区榜数据（用于一键重抓修复历史错位脏数据）。只清榜数据，不动 rank_slice_group 覆盖图。返回删除行数。 */
    public long clearAll() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                long before = count();
                st.executeUpdate("DELETE FROM partition_rank");
                log.warn("partition_rank 已清空（{} 行），等待重抓", before);
                return before;
            } catch (SQLException e) {
                throw new IllegalStateException("清空 partition_rank 失败", e);
            }
        }
    }

    private static void setLong(java.sql.PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, v);
        }
    }

    private static void setInt(java.sql.PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }
}
