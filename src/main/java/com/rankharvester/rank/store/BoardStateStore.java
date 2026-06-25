package com.rankharvester.rank.store;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 排行榜后台配置持久层。
 *
 * <ul>
 *   <li>{@code leaderboard_state(code, enabled, public_visible, updated_at)} —— 是否抓取 / 是否对前端开放</li>
 *   <li>{@code board_field_label(code, field, label)} —— 按榜的字段显示名覆盖</li>
 * </ul>
 * 覆盖目录默认值（动态榜默认不抓、默认不开放）；重启保留。
 */
@Repository
public class BoardStateStore {

    private static final Logger log = LoggerFactory.getLogger(BoardStateStore.class);

    private final Connection conn;

    public BoardStateStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    @PostConstruct
    public void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS leaderboard_state(
                            code           VARCHAR PRIMARY KEY,
                            enabled        BOOLEAN,
                            public_visible BOOLEAN,
                            updated_at     BIGINT
                        )""");
                // 旧库迁移：补 public_visible 列
                try {
                    st.execute("ALTER TABLE leaderboard_state ADD COLUMN IF NOT EXISTS public_visible BOOLEAN");
                } catch (SQLException ignore) {
                    // 列已存在或不支持 IF NOT EXISTS，忽略
                }
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS board_field_label(
                            code  VARCHAR,
                            field VARCHAR,
                            label VARCHAR,
                            PRIMARY KEY(code, field)
                        )""");
                // 字段展示配置：label / 列序 / 显隐 / 渲染类型（覆盖默认；列只有 visible=true 才显示）。
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS board_field_config(
                            code          VARCHAR,
                            field         VARCHAR,
                            label         VARCHAR,
                            display_order INTEGER,
                            visible       BOOLEAN,
                            render_type   VARCHAR,
                            PRIMARY KEY(code, field)
                        )""");
                // 旧 board_field_label 迁移进新表的 label（仅迁移尚不存在的）。
                st.execute(
                        """
                        INSERT INTO board_field_config(code, field, label)
                        SELECT l.code, l.field, l.label FROM board_field_label l
                        WHERE NOT EXISTS (
                            SELECT 1 FROM board_field_config c WHERE c.code = l.code AND c.field = l.field)""");
                // 每榜组的「排名依据」：按哪个字段、升/降序读时排序（名次=排序后行号）。code 此处存榜组 group。
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS board_sort(
                            code       VARCHAR PRIMARY KEY,
                            sort_field VARCHAR,
                            sort_dir   VARCHAR
                        )""");
            }
        }
        log.info("DuckDB leaderboard_state / board_field_config / board_sort 表已就绪");
    }

    /** 排名依据的一个条件：字段 + 方向（ASC/DESC）。多条件按列表顺序为优先级。 */
    public record SortConfig(String field, String dir) {}

    /** 榜组 → 排名依据多条件列表（仅含显式设过的）。spec 存在 sort_field 列：{@code f1:DESC,f2:DESC}。 */
    public Map<String, List<SortConfig>> loadSort() {
        var map = new LinkedHashMap<String, List<SortConfig>>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT code, sort_field, sort_dir FROM board_sort")) {
                while (rs.next()) {
                    map.put(rs.getString(1), parseSpec(rs.getString(2), rs.getString(3)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 board_sort 失败", e);
            }
        }
        return map;
    }

    /** 把多条件列表写回（spec 串入 sort_field，sort_dir 存首条方向兼容旧读法）。 */
    public void upsertSort(String group, List<SortConfig> sorts) {
        String spec = serializeSpec(sorts);
        String firstDir = sorts.isEmpty() ? "DESC" : sorts.get(0).dir();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO board_sort(code, sort_field, sort_dir) VALUES(?,?,?)
                    ON CONFLICT(code) DO UPDATE SET sort_field=excluded.sort_field, sort_dir=excluded.sort_dir""")) {
                ps.setString(1, group);
                ps.setString(2, spec);
                ps.setString(3, firstDir);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 board_sort 失败: " + group, e);
            }
        }
    }

    /** 解析 spec：{@code "f1:DESC,f2:ASC"}；旧单字段（无冒号）则用 legacyDir 列。 */
    public static List<SortConfig> parseSpec(String spec, String legacyDir) {
        var out = new java.util.ArrayList<SortConfig>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        if (spec.indexOf(':') < 0 && spec.indexOf(',') < 0) {
            out.add(new SortConfig(spec.trim(), legacyDir == null ? "DESC" : legacyDir));
            return out;
        }
        for (String part : spec.split(",")) {
            if (part.isBlank()) continue;
            String[] fd = part.split(":");
            if (fd.length >= 1 && !fd[0].isBlank()) {
                out.add(new SortConfig(fd[0].trim(), fd.length > 1 ? fd[1].trim() : "DESC"));
            }
        }
        return out;
    }

    /** 序列化多条件为 {@code "f1:DESC,f2:ASC"}。 */
    public static String serializeSpec(List<SortConfig> sorts) {
        var sb = new StringBuilder();
        for (SortConfig s : sorts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s.field()).append(':').append(s.dir());
        }
        return sb.toString();
    }

    /** 字段配置覆盖（仅含显式设过的项；null 表示该项未覆盖、用默认）。 */
    public record FieldOverride(String label, Integer order, Boolean visible, String render) {}

    /** code → (field → 覆盖)。 */
    public Map<String, Map<String, FieldOverride>> loadFieldConfigs() {
        var map = new LinkedHashMap<String, Map<String, FieldOverride>>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT code, field, label, display_order, visible, render_type FROM board_field_config")) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    String field = rs.getString(2);
                    String label = rs.getString(3);
                    int ord = rs.getInt(4);
                    Integer order = rs.wasNull() ? null : ord;
                    boolean vis = rs.getBoolean(5);
                    Boolean visible = rs.wasNull() ? null : vis;
                    String render = rs.getString(6);
                    map.computeIfAbsent(code, k -> new LinkedHashMap<>())
                            .put(field, new FieldOverride(label, order, visible, render));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 board_field_config 失败", e);
            }
        }
        return map;
    }

    /** 部分更新某字段配置：非 null 的项覆盖，null 的项保持原值。 */
    public void upsertFieldConfig(
            String code, String field, String label, Integer order, Boolean visible, String render) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO board_field_config(code, field, label, display_order, visible, render_type)
                    VALUES(?,?,?,?,?,?)
                    ON CONFLICT(code, field) DO UPDATE SET
                        label=COALESCE(excluded.label, board_field_config.label),
                        display_order=COALESCE(excluded.display_order, board_field_config.display_order),
                        visible=COALESCE(excluded.visible, board_field_config.visible),
                        render_type=COALESCE(excluded.render_type, board_field_config.render_type)""")) {
                ps.setString(1, code);
                ps.setString(2, field);
                if (label == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, label);
                if (order == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, order);
                if (visible == null) ps.setNull(5, java.sql.Types.BOOLEAN); else ps.setBoolean(5, visible);
                if (render == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, render);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 board_field_config 失败: " + code + "/" + field, e);
            }
        }
    }

    /** code → enabled（仅含显式设过的）。 */
    public Map<String, Boolean> loadEnabled() {
        return loadBoolColumn("enabled");
    }

    /** code → public_visible（仅含显式设过的）。 */
    public Map<String, Boolean> loadPublic() {
        return loadBoolColumn("public_visible");
    }

    private Map<String, Boolean> loadBoolColumn(String column) {
        var map = new LinkedHashMap<String, Boolean>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT code, " + column + " FROM leaderboard_state WHERE " + column + " IS NOT NULL")) {
                while (rs.next()) {
                    map.put(rs.getString(1), rs.getBoolean(2));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 leaderboard_state." + column + " 失败", e);
            }
        }
        return map;
    }

    public void upsertEnabled(String code, boolean enabled, long updatedAt) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO leaderboard_state(code,enabled,public_visible,updated_at) VALUES(?,?,NULL,?)
                    ON CONFLICT(code) DO UPDATE SET enabled=excluded.enabled, updated_at=excluded.updated_at""")) {
                ps.setString(1, code);
                ps.setBoolean(2, enabled);
                ps.setLong(3, updatedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 enabled 失败: " + code, e);
            }
        }
    }

    public void upsertPublic(String code, boolean publicVisible, long updatedAt) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO leaderboard_state(code,enabled,public_visible,updated_at) VALUES(?,NULL,?,?)
                    ON CONFLICT(code) DO UPDATE SET public_visible=excluded.public_visible, updated_at=excluded.updated_at""")) {
                ps.setString(1, code);
                ps.setBoolean(2, publicVisible);
                ps.setLong(3, updatedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 public_visible 失败: " + code, e);
            }
        }
    }

    /** code → (field → label)。 */
    public Map<String, Map<String, String>> loadLabels() {
        var map = new LinkedHashMap<String, Map<String, String>>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT code, field, label FROM board_field_label")) {
                while (rs.next()) {
                    map.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getString(3));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 board_field_label 失败", e);
            }
        }
        return map;
    }

    public void upsertLabel(String code, String field, String label) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO board_field_label(code,field,label) VALUES(?,?,?)
                    ON CONFLICT(code,field) DO UPDATE SET label=excluded.label""")) {
                ps.setString(1, code);
                ps.setString(2, field);
                ps.setString(3, label);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 board_field_label 失败: " + code + "/" + field, e);
            }
        }
    }
}
