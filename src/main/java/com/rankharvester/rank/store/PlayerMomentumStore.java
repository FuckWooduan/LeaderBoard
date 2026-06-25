package com.rankharvester.rank.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 玩家「动量」：等级 / 经验随时间的变化量。给隐藏预测判活跃度用——
 * 经验瞬时暴涨(≈开 3000 狮子头满体力)、近期快速升级 = 在认真打本季乱斗 → 预测应更靠前；
 * 经验/等级长期不动 = 可能没打 → 预测靠后。静态的 VIP/境界/战力判断不出这些。
 *
 * <p>表 {@code player_momentum}：每个 gc_id 存「最近一次经验有变化」的读数 + 上一次读数，差值即动量。
 * 由 {@code PlayerMomentumService} 每小时把当前经验榜读一遍写入（经验没变的不动 prev，保留上次活跃窗口）。
 * 注意：列名避开 DuckDB 关键字（{@code at} 是保留字 → 用 {@code obs_at}）。
 */
@Repository
public class PlayerMomentumStore {

    private static final Logger log = LoggerFactory.getLogger(PlayerMomentumStore.class);
    private final Connection conn;

    public PlayerMomentumStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS player_momentum("
                        + "gc_id BIGINT PRIMARY KEY, exp BIGINT, lv INTEGER, obs_at BIGINT,"
                        + "prev_exp BIGINT, prev_lv INTEGER, prev_at BIGINT)");
            } catch (SQLException e) {
                // 动量是非关键功能：建表失败也只记日志、绝不抛异常拖垮整个应用启动（后续读写自然降级为空）。
                log.error("player_momentum 建表失败，动量功能将不可用（不影响其它功能）: {}", e.getMessage());
            }
        }
    }

    /**
     * 记录一批当前 (gc_id, exp, lv) 读数。<b>只对经验有变化的 gc_id</b> 把旧读数移到 prev、写入新读数
     * （经验没变不动，保留上次活跃窗口）。rows 每项 = [gc_id, exp, lv]，now=本次观测时刻 ms。
     */
    public void recordSnapshot(List<long[]> rows, long now) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(
                        "INSERT INTO player_momentum(gc_id,exp,lv,obs_at,prev_exp,prev_lv,prev_at)"
                                + " VALUES(?,?,?,?,NULL,NULL,NULL)"
                                + " ON CONFLICT(gc_id) DO UPDATE SET"
                                + " prev_exp=exp, prev_lv=lv, prev_at=obs_at,"
                                + " exp=excluded.exp, lv=excluded.lv, obs_at=excluded.obs_at"
                                + " WHERE excluded.exp <> exp")) {
                    for (long[] r : rows) {
                        ps.setLong(1, r[0]);
                        ps.setLong(2, r[1]);
                        ps.setLong(3, r[2]);
                        ps.setLong(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                    // best-effort
                }
                log.warn("记录玩家动量失败: {}", e.getMessage());
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignore) {
                    // best-effort
                }
            }
        }
    }

    /** gc_id → [expDelta, lvDelta, hours]：最近一次经验变化相对上一次的增量；无 prev（还没第二次读数）则不含该 gc。 */
    public Map<Long, long[]> momentumByGcId(Collection<Long> gcIds) {
        var out = new HashMap<Long, long[]>();
        if (gcIds == null || gcIds.isEmpty()) {
            return out;
        }
        var ids = new ArrayList<>(new LinkedHashSet<>(gcIds));
        ids.removeIf(Objects::isNull);
        if (ids.isEmpty()) {
            return out;
        }
        String ph = String.join(",", Collections.nCopies(ids.size(), "?"));
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT gc_id, exp-prev_exp, lv-prev_lv, obs_at-prev_at FROM player_momentum"
                            + " WHERE prev_exp IS NOT NULL AND gc_id IN (" + ph + ")")) {
                int i = 1;
                for (Long id : ids) {
                    ps.setLong(i++, id);
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long hours = Math.max(0L, rs.getLong(4)) / 3_600_000L;
                        out.put(rs.getLong(1), new long[] {rs.getLong(2), rs.getLong(3), hours});
                    }
                }
            } catch (SQLException e) {
                log.warn("查玩家动量失败: {}", e.getMessage());
            }
        }
        return out;
    }
}
