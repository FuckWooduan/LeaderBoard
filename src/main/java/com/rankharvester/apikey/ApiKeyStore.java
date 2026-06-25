package com.rankharvester.apikey;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * API key 落地（DuckDB）：第三方调用「玩家在线查询」API 的不透明随机密钥。
 * 后台可创建（带备注）、列出、失效（停用）、删除；记录创建时间 / 最近使用 / 调用次数。
 */
@Repository
public class ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStore.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Connection conn;

    public ApiKeyStore(@Qualifier("duckDbMetaConnection") Connection duckDbMetaConnection) {
        this.conn = duckDbMetaConnection;
    }

    /** 一条 API key 记录。userId=0 表示后台签发（无归属用户）；>0 为用户自助生成。 */
    public record ApiKey(long id, String key, String note, boolean active,
            long createdAt, long lastUsedAt, long callCount, long userId) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SEQUENCE IF NOT EXISTS api_key_seq START 1");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS api_key(
                            id           BIGINT PRIMARY KEY,
                            api_key      VARCHAR UNIQUE,
                            note         VARCHAR,
                            active       BOOLEAN,
                            created_at   BIGINT,
                            last_used_at BIGINT,
                            call_count   BIGINT
                        )""");
                // 老表迁移：用户自助 key 的归属（0=后台签发）
                st.execute("ALTER TABLE api_key ADD COLUMN IF NOT EXISTS user_id BIGINT DEFAULT 0");
            } catch (SQLException e) {
                throw new IllegalStateException("建 api_key 表失败", e);
            }
        }
        log.info("api_key 表就绪（{} 个 key）", all().size());
    }

    /** 生成新 key：sk_ + 40 位十六进制随机串。 */
    private static String genKey() {
        char[] c = new char[40];
        for (int i = 0; i < c.length; i++) {
            c[i] = HEX[RNG.nextInt(16)];
        }
        return "sk_" + new String(c);
    }

    public ApiKey create(String note) {
        return create(note, 0L);
    }

    /** 创建 key；userId>0 表示用户自助生成（每用户一个，由 service 层先删旧）。 */
    public ApiKey create(String note, long userId) {
        long now = System.currentTimeMillis();
        String key = genKey();
        synchronized (conn) {
            try {
                long id;
                try (var rs = conn.createStatement().executeQuery("SELECT nextval('api_key_seq')")) {
                    rs.next();
                    id = rs.getLong(1);
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO api_key(id,api_key,note,active,created_at,last_used_at,call_count,user_id)"
                        + " VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, key);
                    ps.setString(3, note == null ? "" : note);
                    ps.setBoolean(4, true);
                    ps.setLong(5, now);
                    ps.setLong(6, 0L);
                    ps.setLong(7, 0L);
                    ps.setLong(8, userId);
                    ps.executeUpdate();
                }
                return new ApiKey(id, key, note == null ? "" : note, true, now, 0L, 0L, userId);
            } catch (SQLException e) {
                throw new IllegalStateException("创建 api_key 失败", e);
            }
        }
    }

    /** 某用户名下的 key（每用户至多一个；自助重置=删旧建新）。 */
    public ApiKey findByUser(long userId) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT id,api_key,note,active,created_at,last_used_at,call_count,user_id"
                            + " FROM api_key WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
                ps.setLong(1, userId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查用户 api_key 失败", e);
            }
        }
    }

    public void deleteByUser(long userId) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM api_key WHERE user_id=?")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删用户 api_key 失败", e);
            }
        }
    }

    public void setActive(long id, boolean active) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("UPDATE api_key SET active=? WHERE id=?")) {
                ps.setBoolean(1, active);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("改 api_key 状态失败", e);
            }
        }
    }

    public void delete(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM api_key WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删 api_key 失败", e);
            }
        }
    }

    /** 批量回写使用统计（last_used_at / call_count 增量），供 service 定时 flush。 */
    public void bumpUsage(long id, long lastUsedAt, long addCalls) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE api_key SET last_used_at=?, call_count=call_count+? WHERE id=?")) {
                ps.setLong(1, lastUsedAt);
                ps.setLong(2, addCalls);
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // best-effort 统计，失败不影响鉴权
            }
        }
    }

    public List<ApiKey> all() {
        var out = new ArrayList<ApiKey>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT id,api_key,note,active,created_at,last_used_at,call_count,user_id"
                                    + " FROM api_key ORDER BY id DESC")) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            } catch (SQLException e) {
                log.warn("读 api_key 列表失败: {}", e.getMessage());
            }
        }
        return out;
    }

    private static ApiKey read(java.sql.ResultSet rs) throws SQLException {
        return new ApiKey(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8));
    }
}
