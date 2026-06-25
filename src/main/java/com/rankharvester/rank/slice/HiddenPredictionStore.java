package com.rankharvester.rank.slice;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 隐藏玩家预测结果落库（表 {@code hidden_prediction}）。批量预测器写入，board 读路径直接合并展示。
 *
 * <p>每条 = 某 (rankKey, 分区, 名次) 的预测：身份 predicted_name + 置信度，乱斗榜还含预测分数。
 * 与分区榜数据共用 {@code duckDbSliceConnection}（MVCC，不抢主连接）。
 */
@Repository
public class HiddenPredictionStore {

    private static final Logger log = LoggerFactory.getLogger(HiddenPredictionStore.class);

    private final Connection conn;

    public HiddenPredictionStore(@Qualifier("duckDbSliceConnection") Connection duckDbSliceConnection) {
        this.conn = duckDbSliceConnection;
    }

    /** 一条预测。via: det=确定性(上赛季递归) / AI=模型。score 仅内部去重用，已不对外展示（预测分数删除）。 */
    public record Pred(int rank, String name, double confidence, Long score, double scoreConfidence,
            String via, String reason, long predictedAt) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS hidden_prediction(
                            rank_key       VARCHAR,
                            partition_idx  INTEGER,
                            rank           INTEGER,
                            predicted_name VARCHAR,
                            confidence     DOUBLE,
                            predicted_score BIGINT,
                            score_confidence DOUBLE,
                            via            VARCHAR,
                            reason         VARCHAR,
                            predicted_at   BIGINT,
                            PRIMARY KEY (rank_key, partition_idx, rank)
                        )""");
            } catch (SQLException e) {
                throw new IllegalStateException("建 hidden_prediction 表失败", e);
            }
        }
        log.info("hidden_prediction 表就绪（{} 条）", count());
    }

    /** 整段替换某 (rankKey, 分区) 的预测。 */
    public void replacePartition(String rankKey, int partition, List<Pred> preds) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.prepareStatement(
                        "DELETE FROM hidden_prediction WHERE rank_key=? AND partition_idx=?")) {
                    del.setString(1, rankKey);
                    del.setInt(2, partition);
                    del.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO hidden_prediction(rank_key,partition_idx,rank,predicted_name,confidence,"
                        + "predicted_score,score_confidence,via,reason,predicted_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    for (Pred p : preds) {
                        ps.setString(1, rankKey);
                        ps.setInt(2, partition);
                        ps.setInt(3, p.rank());
                        ps.setString(4, p.name());
                        ps.setDouble(5, p.confidence());
                        if (p.score() == null) {
                            ps.setNull(6, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(6, p.score());
                        }
                        ps.setDouble(7, p.scoreConfidence());
                        ps.setString(8, p.via());
                        ps.setString(9, p.reason());
                        ps.setLong(10, p.predictedAt());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("写 hidden_prediction 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
    }

    /** 某 (rankKey, 分区) 的预测：rank → Pred。 */
    public Map<Integer, Pred> forPartition(String rankKey, int partition) {
        var out = new LinkedHashMap<Integer, Pred>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,predicted_name,confidence,predicted_score,score_confidence,via,reason,predicted_at"
                    + " FROM hidden_prediction WHERE rank_key=? AND partition_idx=?")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sc = rs.getLong(4);
                        Long score = rs.wasNull() ? null : sc;
                        out.put(rs.getInt(1), new Pred(rs.getInt(1), rs.getString(2), rs.getDouble(3),
                                score, rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                    }
                }
            } catch (SQLException e) {
                log.warn("forPartition 查 hidden_prediction 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 某 rankKey 最近一次预测时间（max predicted_at），无则 0。 */
    public long lastPredictedAt(String rankKey) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(predicted_at),0) FROM hidden_prediction WHERE rank_key=?")) {
                ps.setString(1, rankKey);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 某 rankKey 预测分数最高的前 n 条（邮件用）。无分数的排后。 */
    public List<Pred> topByScore(String rankKey, int n) {
        var out = new ArrayList<Pred>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT rank,predicted_name,confidence,predicted_score,score_confidence,via,reason,predicted_at,partition_idx"
                    + " FROM hidden_prediction WHERE rank_key=?"
                    + " ORDER BY predicted_score DESC NULLS LAST LIMIT ?")) {
                ps.setString(1, rankKey);
                ps.setInt(2, n);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sc = rs.getLong(4);
                        Long score = rs.wasNull() ? null : sc;
                        out.add(new Pred(rs.getInt(1), rs.getString(2), rs.getDouble(3),
                                score, rs.getDouble(5), rs.getString(6),
                                "分区" + (rs.getInt(9) + 1), rs.getLong(8)));
                    }
                }
            } catch (SQLException e) {
                log.warn("topByScore 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 清空全部预测（换了预测算法后用）。返回删除条数。 */
    public long clearAll() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                long before = count();
                st.executeUpdate("DELETE FROM hidden_prediction");
                return before;
            } catch (SQLException e) {
                log.warn("clearAll hidden_prediction 失败: {}", e.getMessage());
                return 0;
            }
        }
    }

    public void clear(String rankKey) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM hidden_prediction WHERE rank_key=?")) {
                ps.setString(1, rankKey);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("clear hidden_prediction 失败: {}", e.getMessage());
            }
        }
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*) FROM hidden_prediction")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }
}
