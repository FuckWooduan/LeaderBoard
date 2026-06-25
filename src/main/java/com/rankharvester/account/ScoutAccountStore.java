package com.rankharvester.account;

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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「频道大厅」侦察号的 DuckDB 持久层（{@code scout_account} 表）—— 与抓榜 {@code game_account} 表
 * <strong>物理隔离</strong>，结构上保证侦察号永不进抓榜池。
 *
 * <p>用 {@code duckDbMetaConnection}（与 API key / 订阅同一条元数据连接），避开抓榜主连接的写入串行化死锁
 * （见 commit f9bd675）。后台可增删改查 + 启停；改完由 {@link ScoutAccountRegistry#reload()} 热生效，无需重启。
 *
 * <p>{@code districts} 存对外区号的 JSON 数组（一号多区，如 {@code [9,5,18]}）；{@code level} 可空（null=懒登自拼）。
 */
@Repository
public class ScoutAccountStore {

    private static final Logger log = LoggerFactory.getLogger(ScoutAccountStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Connection conn;

    public ScoutAccountStore(@Qualifier("duckDbMetaConnection") Connection duckDbMetaConnection) {
        this.conn = duckDbMetaConnection;
    }

    /**
     * 一条侦察号记录。{@code districts}=对外区号列表；{@code level}=手填等级（null=未知）；
     * {@code probedLevels}=探测/懒登抓到的每区真实等级（对外区号→等级）。
     */
    public record Row(long id, String account, String password, String pauth, String uid,
            List<String> districts, Integer level, Map<String, Integer> probedLevels,
            boolean enabled, long createdAt, long updatedAt) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SEQUENCE IF NOT EXISTS scout_account_seq START 1");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS scout_account(
                            id            BIGINT PRIMARY KEY,
                            account       VARCHAR NOT NULL,
                            password      VARCHAR,
                            pauth         VARCHAR,
                            uid           VARCHAR,
                            districts     VARCHAR,
                            level         INTEGER,
                            probed_levels VARCHAR,
                            enabled       BOOLEAN,
                            created_at    BIGINT,
                            updated_at    BIGINT
                        )""");
                // 老表补列（探测/懒登抓到的每区真实等级 JSON：{"9":94,"5":80}）
                st.execute("ALTER TABLE scout_account ADD COLUMN IF NOT EXISTS probed_levels VARCHAR");
            } catch (SQLException e) {
                throw new IllegalStateException("建 scout_account 表失败", e);
            }
        }
        log.info("scout_account 表就绪（{} 个侦察号）", all().size());
    }

    /** 新增一条，返回分配到的 id。 */
    public long create(String account, String password, String pauth, String uid,
            List<String> districts, Integer level, boolean enabled) {
        long now = System.currentTimeMillis();
        synchronized (conn) {
            try {
                long id;
                try (var rs = conn.createStatement().executeQuery("SELECT nextval('scout_account_seq')")) {
                    rs.next();
                    id = rs.getLong(1);
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO scout_account(id,account,password,pauth,uid,districts,level,enabled,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, account);
                    ps.setString(3, password);
                    ps.setString(4, pauth);
                    ps.setString(5, uid);
                    ps.setString(6, toJson(districts));
                    setLevel(ps, 7, level);
                    ps.setBoolean(8, enabled);
                    ps.setLong(9, now);
                    ps.setLong(10, now);
                    ps.executeUpdate();
                }
                return id;
            } catch (SQLException e) {
                throw new IllegalStateException("创建侦察号失败", e);
            }
        }
    }

    /** 更新一条（按 id）。 */
    public void update(long id, String account, String password, String pauth, String uid,
            List<String> districts, Integer level, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE scout_account SET account=?,password=?,pauth=?,uid=?,districts=?,level=?,enabled=?,updated_at=?"
                    + " WHERE id=?")) {
                ps.setString(1, account);
                ps.setString(2, password);
                ps.setString(3, pauth);
                ps.setString(4, uid);
                ps.setString(5, toJson(districts));
                setLevel(ps, 6, level);
                ps.setBoolean(7, enabled);
                ps.setLong(8, System.currentTimeMillis());
                ps.setLong(9, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新侦察号失败", e);
            }
        }
    }

    public void delete(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM scout_account WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除侦察号失败", e);
            }
        }
    }

    public void setEnabled(long id, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("UPDATE scout_account SET enabled=?,updated_at=? WHERE id=?")) {
                ps.setBoolean(1, enabled);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("改侦察号状态失败", e);
            }
        }
    }

    public List<Row> all() {
        var out = new ArrayList<Row>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT id,account,password,pauth,uid,districts,level,probed_levels,enabled,created_at,updated_at"
                            + " FROM scout_account ORDER BY id")) {
                while (rs.next()) {
                    int lv = rs.getInt(7);
                    Integer level = rs.wasNull() ? null : lv;
                    out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), fromJson(rs.getString(6)), level, levelsFromJson(rs.getString(8)),
                            rs.getBoolean(9), rs.getLong(10), rs.getLong(11)));
                }
            } catch (SQLException e) {
                log.warn("读 scout_account 列表失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 探测/懒登抓到某 (号,区) 等级 → 合并进该号的 probed_levels JSON 并持久化。best-effort。 */
    public void putProbedLevel(long id, String district, int level) {
        synchronized (conn) {
            try {
                Map<String, Integer> map;
                try (var ps = conn.prepareStatement("SELECT probed_levels FROM scout_account WHERE id=?")) {
                    ps.setLong(1, id);
                    try (var rs = ps.executeQuery()) {
                        map = rs.next() ? levelsFromJson(rs.getString(1)) : new LinkedHashMap<>();
                    }
                }
                if (level == map.getOrDefault(district, Integer.MIN_VALUE)) {
                    return; // 无变化，省一次写
                }
                map.put(district, level);
                try (var ps = conn.prepareStatement(
                        "UPDATE scout_account SET probed_levels=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, levelsToJson(map));
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                log.debug("回写 probed_levels 失败 id={}: {}", id, e.getMessage());
            }
        }
    }

    /** 全区探测后把发现的区列表写回 districts。 */
    public void setDistricts(long id, List<String> districts) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE scout_account SET districts=?,updated_at=? WHERE id=?")) {
                ps.setString(1, toJson(districts));
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写回 districts 失败 id=" + id, e);
            }
        }
    }

    private static void setLevel(java.sql.PreparedStatement ps, int idx, Integer level) throws SQLException {
        if (level == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, level);
        }
    }

    private static String toJson(List<String> districts) {
        try {
            return MAPPER.writeValueAsString(districts == null ? List.of() : districts);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = MAPPER.readValue(json, List.class);
            var out = new ArrayList<String>(raw.size());
            for (Object o : raw) {
                if (o != null) out.add(String.valueOf(o).trim());
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String levelsToJson(Map<String, Integer> levels) {
        try {
            return MAPPER.writeValueAsString(levels == null ? Map.of() : levels);
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> levelsFromJson(String json) {
        var out = new LinkedHashMap<String, Integer>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            raw.forEach((k, v) -> {
                if (v instanceof Number n) out.put(k, n.intValue());
            });
        } catch (RuntimeException ignore) {
            // 异型 JSON → 空表
        }
        return out;
    }
}
