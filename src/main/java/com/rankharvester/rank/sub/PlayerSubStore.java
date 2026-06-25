package com.rankharvester.rank.sub;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 玩家「变化订阅」存储：按 GCID 订阅某玩家的<b>改名 / VIP 提升 / 境界提升</b>，变化即邮件通知。
 *
 * <p>检测源是<b>同服战力榜</b> {@code REDUCED:382:<loginId>}（含 gc_id/角色名/VIP/境界）。
 * 每次该榜刷新后比对订阅里记录的 last_* 与最新值，变化即发邮件并更新 last_*。
 */
@Repository
public class PlayerSubStore {

    private static final Logger log = LoggerFactory.getLogger(PlayerSubStore.class);

    private final Connection conn;

    public PlayerSubStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    public record Sub(String email, long gcId, String server, String lastName, Integer lastVip,
            Integer lastRealm, boolean subRename, boolean subVip, boolean subRealm) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_change_sub(
                            email      VARCHAR,
                            gc_id      BIGINT,
                            server     VARCHAR,
                            last_name  VARCHAR,
                            last_vip   INTEGER,
                            last_realm INTEGER,
                            sub_rename BOOLEAN,
                            sub_vip    BOOLEAN,
                            sub_realm  BOOLEAN,
                            updated_at BIGINT,
                            PRIMARY KEY (email, gc_id)
                        )""");
            } catch (SQLException e) {
                throw new IllegalStateException("建 player_change_sub 表失败", e);
            }
        }
        log.info("DuckDB player_change_sub（玩家变化订阅）表已就绪");
    }

    /** 新增/更新订阅（整条覆盖：含初始 last_* 基线与订阅项）。 */
    public void subscribe(String email, long gcId, String server, String name, Integer vip, Integer realm,
            boolean rename, boolean vipFlag, boolean realmFlag, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO player_change_sub(email,gc_id,server,last_name,last_vip,last_realm,"
                    + "sub_rename,sub_vip,sub_realm,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT (email,gc_id) DO UPDATE SET server=excluded.server,last_name=excluded.last_name,"
                    + "last_vip=excluded.last_vip,last_realm=excluded.last_realm,sub_rename=excluded.sub_rename,"
                    + "sub_vip=excluded.sub_vip,sub_realm=excluded.sub_realm,updated_at=excluded.updated_at")) {
                ps.setString(1, email);
                ps.setLong(2, gcId);
                ps.setString(3, server);
                ps.setString(4, name);
                setInt(ps, 5, vip);
                setInt(ps, 6, realm);
                ps.setBoolean(7, rename);
                ps.setBoolean(8, vipFlag);
                ps.setBoolean(9, realmFlag);
                ps.setLong(10, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写 player_change_sub 失败", e);
            }
        }
    }

    public void unsubscribe(String email, long gcId) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM player_change_sub WHERE email=? AND gc_id=?")) {
                ps.setString(1, email);
                ps.setLong(2, gcId);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    /** 某 server（loginId）下的全部订阅。 */
    public List<Sub> forServer(String server) {
        var out = new ArrayList<Sub>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT email,gc_id,server,last_name,last_vip,last_realm,sub_rename,sub_vip,sub_realm"
                    + " FROM player_change_sub WHERE server=?")) {
                ps.setString(1, server);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Sub(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                intOrNull(rs, 5), intOrNull(rs, 6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9)));
                    }
                }
            } catch (SQLException e) {
                log.warn("查 player_change_sub 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 通知后更新 last_*（基线推进）。 */
    public void updateState(String email, long gcId, String name, Integer vip, Integer realm, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE player_change_sub SET last_name=?,last_vip=?,last_realm=?,updated_at=? WHERE email=? AND gc_id=?")) {
                ps.setString(1, name);
                setInt(ps, 2, vip);
                setInt(ps, 3, realm);
                ps.setLong(4, now);
                ps.setString(5, email);
                ps.setLong(6, gcId);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM player_change_sub")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    private static void setInt(java.sql.PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    private static Integer intOrNull(java.sql.ResultSet rs, int idx) throws SQLException {
        int v = rs.getInt(idx);
        return rs.wasNull() ? null : v;
    }
}
