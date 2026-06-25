package com.rankharvester.weapon;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 武器库落地：表 {@code weapon}，每次同步<b>全量替换</b>（DELETE 全表 + 批量 INSERT，约 2000 行）。
 * 与其它 DuckDB store 共享同一连接，以连接对象为监视器串行化访问。
 */
@Repository
public class WeaponStore {

    private static final Logger log = LoggerFactory.getLogger(WeaponStore.class);

    private static final String INSERT =
            "INSERT INTO weapon(id,name,code_name,type,sub_type,type_label,description,specific,tags,icon,"
            + "penetrate,fire_rate,power,accurate,stability,weight,bullet,bullet_max,search_all,updated_at)"
            + " VALUES(" + "?,".repeat(19) + "?)";

    private final Connection conn;

    public WeaponStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    /** 全量替换武器库（事务内 DELETE 全表 + 批量 INSERT）。 */
    public void replaceAll(List<Weapon> weapons, long fetchedAt) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.createStatement()) {
                    del.execute("DELETE FROM weapon");
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
                    for (Weapon w : weapons) {
                        int i = 1;
                        ps.setLong(i++, w.id());
                        ps.setString(i++, w.name());
                        ps.setString(i++, w.codeName());
                        ps.setInt(i++, w.type());
                        ps.setInt(i++, w.subType());
                        ps.setString(i++, w.typeLabel());
                        ps.setString(i++, w.description());
                        ps.setString(i++, w.specific());
                        ps.setString(i++, w.tags());
                        ps.setString(i++, w.icon());
                        setD(ps, i++, w.penetrate());
                        setD(ps, i++, w.fireRate());
                        setD(ps, i++, w.power());
                        setD(ps, i++, w.accurate());
                        setD(ps, i++, w.stability());
                        setD(ps, i++, w.weight());
                        setD(ps, i++, w.bullet());
                        setD(ps, i++, w.bulletMax());
                        // search_all：该武器全部字符串字段（去 HTML）拼接，供「全字符串模糊搜索」
                        ps.setString(i++, stripHtml(joinNonNull(w.name(), w.codeName(), w.typeLabel(),
                                w.description(), w.specific(), w.tags())));
                        ps.setLong(i, fetchedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("替换 weapon 失败", e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.info("weapon 库已全量替换：{} 把武器", weapons.size());
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM weapon")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 武器全字符串模糊匹配数。 */
    public long countWeapons(String q) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM weapon WHERE search_all ILIKE ?")) {
                ps.setString(1, like);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 武器全字符串模糊搜索（分页，按 id 升序）。 */
    public List<Weapon.Brief> search(String q, int offset, int limit) {
        var out = new ArrayList<Weapon.Brief>();
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT id,name,code_name,type_label FROM weapon"
                    + " WHERE search_all ILIKE ? ORDER BY id LIMIT ? OFFSET ?")) {
                ps.setString(1, like);
                ps.setInt(2, Math.max(0, limit));
                ps.setInt(3, Math.max(0, offset));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Weapon.Brief(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("搜索 weapon 失败", e);
            }
        }
        return out;
    }

    // ── 通用辅助条目（武器技能/配件/词缀等） ──────────────────────────────
    /**
     * 一条可搜索的辅助条目。{@code searchText} 为该记录全部字符串拼接（去 HTML），读出时为 null；
     * {@code payload} 为该记录完整原始 JSON（供点击展开看全部字段）。
     */
    public record Entry(String category, String catLabel, String key, String name, String detail,
            String searchText, String payload) {}

    private static String joinNonNull(String... vals) {
        var sb = new StringBuilder();
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private static final String INSERT_ENTRY =
            "INSERT INTO weapon_entry(category,cat_label,key,name,detail,search_text,payload_json,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?)";

    /** 全量替换辅助条目。 */
    public void replaceEntries(List<Entry> entries, long fetchedAt) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var del = conn.createStatement()) {
                    del.execute("DELETE FROM weapon_entry");
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT_ENTRY)) {
                    for (Entry e : entries) {
                        ps.setString(1, e.category());
                        ps.setString(2, e.catLabel());
                        ps.setString(3, e.key());
                        ps.setString(4, e.name());
                        ps.setString(5, e.detail());
                        ps.setString(6, e.searchText() != null ? e.searchText()
                                : stripHtml(joinNonNull(e.name(), e.detail())));
                        ps.setString(7, e.payload());
                        ps.setLong(8, fetchedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException ex) {
                rollbackQuietly();
                throw new IllegalStateException("替换 weapon_entry 失败", ex);
            } finally {
                setAutoCommitQuietly();
            }
        }
        log.info("weapon_entry 已全量替换：{} 条", entries.size());
    }

    public long entryCount() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM weapon_entry")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 武器库同步状态：数量 + 最近全量替换时间。 */
    public SyncStatus syncStatus() {
        long weapons = 0L;
        long weaponUpdatedAt = 0L;
        long entries = 0L;
        long entryUpdatedAt = 0L;
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*), COALESCE(MAX(updated_at),0) FROM weapon")) {
                if (rs.next()) {
                    weapons = rs.getLong(1);
                    weaponUpdatedAt = rs.getLong(2);
                }
            } catch (SQLException ignore) {
                // 表可能尚未初始化完成
            }
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*), COALESCE(MAX(updated_at),0) FROM weapon_entry")) {
                if (rs.next()) {
                    entries = rs.getLong(1);
                    entryUpdatedAt = rs.getLong(2);
                }
            } catch (SQLException ignore) {
                // 表可能尚未初始化完成
            }
        }
        return new SyncStatus(weapons, weaponUpdatedAt, entries, entryUpdatedAt,
                Math.max(weaponUpdatedAt, entryUpdatedAt));
    }

    /** 辅助条目模糊匹配数。 */
    public long countEntries(String q) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM weapon_entry WHERE search_text ILIKE ?")) {
                ps.setString(1, like);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 模糊搜索辅助条目（去 HTML 文本匹配，分页，按 类别→key）。 */
    public List<Entry> searchEntries(String q, int offset, int limit) {
        var out = new ArrayList<Entry>();
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        if (limit <= 0) {
            return out;
        }
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT category,cat_label,key,name,detail,payload_json FROM weapon_entry"
                    + " WHERE search_text ILIKE ? ORDER BY category, key LIMIT ? OFFSET ?")) {
                ps.setString(1, like);
                ps.setInt(2, limit);
                ps.setInt(3, Math.max(0, offset));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), null, rs.getString(6)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("搜索 weapon_entry 失败", e);
            }
        }
        return out;
    }

    /** 去 HTML 标签 + 常见实体，供搜索文本用。 */
    private static String stripHtml(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    public Optional<Weapon> get(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT * FROM weapon WHERE id = ? LIMIT 1")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 weapon 失败: " + id, e);
            }
        }
    }

    /** 按精确武器名取（QQ 机器人查询用）。 */
    public Optional<Weapon> getByExactName(String name) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT * FROM weapon WHERE name = ? ORDER BY id LIMIT 1")) {
                ps.setString(1, name);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 weapon 失败: " + name, e);
            }
        }
    }

    private static Weapon map(ResultSet rs) throws SQLException {
        return new Weapon(
                rs.getLong("id"), rs.getString("name"), rs.getString("code_name"),
                rs.getInt("type"), rs.getInt("sub_type"), rs.getString("type_label"),
                rs.getString("description"), rs.getString("specific"), rs.getString("tags"), rs.getString("icon"),
                getD(rs, "penetrate"), getD(rs, "fire_rate"), getD(rs, "power"), getD(rs, "accurate"),
                getD(rs, "stability"), getD(rs, "weight"), getD(rs, "bullet"), getD(rs, "bullet_max"));
    }

    private static Double getD(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static void setD(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    private void rollbackQuietly() {
        try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
    }

    private void setAutoCommitQuietly() {
        try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
    }

    public record SyncStatus(long weapons, long weaponUpdatedAt, long entries, long entryUpdatedAt, long updatedAt) {}
}
