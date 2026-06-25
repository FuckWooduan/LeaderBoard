package com.rankharvester.account;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 游戏账号的 DuckDB 持久层（{@code game_account} 表）。
 *
 * <p>仅在启动期写入（由 {@link GameAccountSeeder} 灌种子）、由 {@link AccountRegistry}
 * 一次性读入内存，运行期不再触碰，故与快照写入不构成并发；对共享连接以连接对象自身加锁串行化。
 */
@Repository
public class GameAccountStore {

    private static final Logger log = LoggerFactory.getLogger(GameAccountStore.class);

    private final Connection conn;

    public GameAccountStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    @PostConstruct
    void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS game_account(
                            id                  BIGINT PRIMARY KEY,
                            account             VARCHAR NOT NULL,
                            password            VARCHAR NOT NULL,
                            pauth               VARCHAR,
                            uid                 VARCHAR,
                            servers             VARCHAR,
                            available           BOOLEAN,
                            enabled             BOOLEAN,
                            created_at          VARCHAR,
                            updated_at          VARCHAR,
                            checked_at          VARCHAR,
                            last_used_at        VARCHAR,
                            next_pauth_check_at  VARCHAR
                        )""");
            }
        }
        log.info("DuckDB game_account 表已就绪（现有 {} 个账号）", count());
    }

    public long count() {
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*) FROM game_account")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                throw new IllegalStateException("统计 game_account 失败", e);
            }
        }
    }

    /** 批量插入（id 冲突则整行覆盖）。 */
    public int insertBatch(List<PgGameAccountDump.SeedRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(
                        """
                        INSERT INTO game_account(id,account,password,pauth,uid,servers,available,enabled,
                            created_at,updated_at,checked_at,last_used_at,next_pauth_check_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(id) DO UPDATE SET
                            account=excluded.account, password=excluded.password, pauth=excluded.pauth,
                            uid=excluded.uid, servers=excluded.servers, available=excluded.available,
                            enabled=excluded.enabled, updated_at=excluded.updated_at,
                            checked_at=excluded.checked_at, last_used_at=excluded.last_used_at,
                            next_pauth_check_at=excluded.next_pauth_check_at""")) {
                    for (var r : rows) {
                        ps.setLong(1, r.id());
                        ps.setString(2, r.account());
                        ps.setString(3, r.password());
                        ps.setString(4, r.pauth());
                        ps.setString(5, r.uid());
                        ps.setString(6, r.serversJson());
                        ps.setBoolean(7, r.available());
                        ps.setBoolean(8, r.enabled());
                        ps.setString(9, r.createdAt());
                        ps.setString(10, r.updatedAt());
                        ps.setString(11, r.checkedAt());
                        ps.setString(12, r.lastUsedAt());
                        ps.setString(13, r.nextPauthCheckAt());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("批量插入 game_account 失败", e);
            } finally {
                setAutoCommitQuietly();
            }
        }
        return rows.size();
    }

    /** 读取全部账号为领域对象。 */
    public List<GameAccount> all() {
        var out = new ArrayList<GameAccount>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT id,account,password,servers,pauth,uid,enabled,available FROM game_account ORDER BY id")) {
                while (rs.next()) {
                    out.add(new GameAccount(
                            String.valueOf(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3),
                            parseServers(rs.getString(4)),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getBoolean(7),
                            rs.getBoolean(8)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 game_account 失败", e);
            }
        }
        return out;
    }

    /**
     * 后台新增一个账号，返回分配到的 id。
     *
     * <p>id 取 {@code max(现有, ADMIN_ID_BASE) + 1}：admin 手建号落在高位段（≥{@value #ADMIN_ID_BASE}），
     * 与 PG dump 导入的小 id 不冲突，后续 dump 覆盖也碰不到这段。
     */
    public long create(String account, String password, String pauth, String uid,
            String serversJson, boolean enabled) {
        String now = String.valueOf(System.currentTimeMillis());
        synchronized (conn) {
            long id = nextAdminId();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO game_account(id,account,password,pauth,uid,servers,available,enabled,"
                    + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, account);
                ps.setString(3, password == null ? "" : password);
                ps.setString(4, pauth);
                ps.setString(5, uid);
                ps.setString(6, serversJson);
                ps.setBoolean(7, true);
                ps.setBoolean(8, enabled);
                ps.setString(9, now);
                ps.setString(10, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("创建 game_account 失败", e);
            }
            return id;
        }
    }

    /** 后台编辑账号（account/password/pauth/uid/servers/enabled）。调用方已持 conn 锁外。 */
    public void update(long id, String account, String password, String pauth, String uid,
            String serversJson, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE game_account SET account=?,password=?,pauth=?,uid=?,servers=?,enabled=?,updated_at=?"
                    + " WHERE id=?")) {
                ps.setString(1, account);
                ps.setString(2, password == null ? "" : password);
                ps.setString(3, pauth);
                ps.setString(4, uid);
                ps.setString(5, serversJson);
                ps.setBoolean(6, enabled);
                ps.setString(7, String.valueOf(System.currentTimeMillis()));
                ps.setLong(8, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 game_account 失败: " + id, e);
            }
        }
    }

    public void delete(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM game_account WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除 game_account 失败: " + id, e);
            }
        }
    }

    public void setEnabled(long id, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE game_account SET enabled=?,updated_at=? WHERE id=?")) {
                ps.setBoolean(1, enabled);
                ps.setString(2, String.valueOf(System.currentTimeMillis()));
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("改 game_account 状态失败: " + id, e);
            }
        }
    }

    /** admin 手建号 id 起始基线（高位段，避开 PG dump 的小 id）。 */
    private static final long ADMIN_ID_BASE = 9_000_000_000L;

    /** 下一个 admin 账号 id（调用方已持 conn 锁）。 */
    private long nextAdminId() {
        try (var st = conn.createStatement();
                var rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM game_account")) {
            long max = rs.next() ? rs.getLong(1) : 0L;
            return Math.max(max, ADMIN_ID_BASE) + 1;
        } catch (SQLException e) {
            throw new IllegalStateException("分配 game_account id 失败", e);
        }
    }

    /** 从某账号的 servers 列表移除一个大区（loginV3 rc=4 该区封禁时调用），持久化。 */
    public void removeServer(long id, String server) {
        synchronized (conn) {
            String json = null;
            try (var ps = conn.prepareStatement("SELECT servers FROM game_account WHERE id = ?")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        json = rs.getString(1);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 servers 失败: " + id, e);
            }
            if (json == null) {
                return;
            }
            var list = parseServers(json);
            if (!list.remove(server)) {
                return; // 该区本就不在列表
            }
            String newJson = "[" + String.join(", ", list) + "]";
            try (var ps = conn.prepareStatement(
                    "UPDATE game_account SET servers = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, newJson);
                ps.setString(2, String.valueOf(System.currentTimeMillis()));
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 servers 失败: " + id, e);
            }
        }
    }

    /** 给某账号的 servers 列表加回一个大区（封号解除后的 7 天复检调用），持久化。已在列表则不动。 */
    public void addServer(long id, String server) {
        synchronized (conn) {
            String json = null;
            try (var ps = conn.prepareStatement("SELECT servers FROM game_account WHERE id = ?")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        json = rs.getString(1);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 servers 失败: " + id, e);
            }
            if (json == null) {
                return;
            }
            var list = parseServers(json);
            if (list.contains(server)) {
                return; // 已在列表
            }
            list.add(server);
            String newJson = "[" + String.join(", ", list) + "]";
            try (var ps = conn.prepareStatement(
                    "UPDATE game_account SET servers = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, newJson);
                ps.setString(2, String.valueOf(System.currentTimeMillis()));
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 servers 失败: " + id, e);
            }
        }
    }

    /** 把<b>所有账号</b>的 servers 一次性设为同一个列表（"全开所有区"）。返回受影响行数。 */
    public int setAllServers(String serversJson) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("UPDATE game_account SET servers = ?, updated_at = ?")) {
                ps.setString(1, serversJson);
                ps.setString(2, String.valueOf(System.currentTimeMillis()));
                int n = ps.executeUpdate();
                log.info("game_account 全部 {} 个账号 servers 已设为 {}", n, serversJson);
                return n;
            } catch (SQLException e) {
                throw new IllegalStateException("批量设置 servers 失败", e);
            }
        }
    }

    /** 解析 jsonb 整型数组文本 "[13, 11, 9]" → ["13","11","9"]。 */
    static List<String> parseServers(String json) {
        var list = new ArrayList<String>();
        if (json == null) {
            return list;
        }
        String body = json.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        if (body.isBlank()) {
            return list;
        }
        for (String tok : body.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
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
