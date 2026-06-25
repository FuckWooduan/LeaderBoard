package com.rankharvester.rank.slice;

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
 * 分区排行榜<b>邮件订阅</b>落地：订阅某 (rankKey, 分区) 里某玩家，排名变动即发邮件。
 * 走分区获取专用第二连接。
 */
@Repository
public class SliceSubscriptionStore {

    private static final Logger log = LoggerFactory.getLogger(SliceSubscriptionStore.class);

    private final Connection conn;

    public SliceSubscriptionStore(@Qualifier("duckDbSliceConnection") Connection duckDbSliceConnection) {
        this.conn = duckDbSliceConnection;
    }

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS partition_sub(
                            email         VARCHAR,
                            rank_key      VARCHAR,
                            partition_idx INTEGER,
                            char_name     VARCHAR,
                            last_rank     INTEGER,
                            created_at    BIGINT,
                            PRIMARY KEY (email, rank_key, partition_idx, char_name)
                        )""");
                // 防轰炸：上次发通知的时刻（同一订阅两封通知之间至少间隔冷却时间）
                st.execute("ALTER TABLE partition_sub ADD COLUMN IF NOT EXISTS last_notified_at BIGINT DEFAULT 0");
            } catch (SQLException e) {
                throw new IllegalStateException("建 partition_sub 表失败", e);
            }
        }
        log.info("partition_sub 表就绪（{} 个订阅）", count());
    }

    /** 新增订阅；{@code currentRank} 为订阅时该玩家当前名次（null=暂不在榜）。 */
    public void subscribe(String email, String rankKey, int partition, String charName, Integer currentRank) {
        long now = System.currentTimeMillis();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO partition_sub(email,rank_key,partition_idx,char_name,last_rank,created_at)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT (email,rank_key,partition_idx,char_name)"
                    + " DO UPDATE SET last_rank=excluded.last_rank")) {
                ps.setString(1, email);
                ps.setString(2, rankKey);
                ps.setInt(3, partition);
                ps.setString(4, charName);
                if (currentRank == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, currentRank);
                }
                ps.setLong(6, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写订阅失败", e);
            }
        }
    }

    public void unsubscribe(String email, String rankKey, int partition, String charName) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM partition_sub WHERE email=? AND rank_key=? AND partition_idx=? AND char_name=?")) {
                ps.setString(1, email);
                ps.setString(2, rankKey);
                ps.setInt(3, partition);
                ps.setString(4, charName);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    /** 某 (rankKey, 分区) 的全部订阅。 */
    public List<Sub> forPartition(String rankKey, int partition) {
        var out = new ArrayList<Sub>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT email,char_name,last_rank,COALESCE(last_notified_at,0)"
                            + " FROM partition_sub WHERE rank_key=? AND partition_idx=?")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int lr = rs.getInt(3);
                        Integer lastRank = rs.wasNull() ? null : lr;
                        out.add(new Sub(rs.getString(1), rs.getString(2), lastRank, rs.getLong(4)));
                    }
                }
            } catch (SQLException e) {
                log.warn("读订阅失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 更新某订阅记录的 last_rank（排名变动通知后调用），并记录通知时刻（防轰炸冷却）。 */
    public void updateRank(String email, String rankKey, int partition, String charName, Integer newRank) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE partition_sub SET last_rank=?, last_notified_at=?"
                            + " WHERE email=? AND rank_key=? AND partition_idx=? AND char_name=?")) {
                if (newRank == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(1, newRank);
                }
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, email);
                ps.setString(4, rankKey);
                ps.setInt(5, partition);
                ps.setString(6, charName);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    /** 是否有任何 (rankKey, 分区) 的订阅（抓取后判断是否需要比对，省开销）。 */
    public boolean hasAny() {
        return count() > 0;
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM partition_sub")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 一条订阅：收件邮箱 + 角色名 + 上次通知的名次（null=上次不在榜）+ 上次通知时刻（0=从未）。 */
    public record Sub(String email, String charName, Integer lastRank, long lastNotifiedAt) {}
}
