package com.rankharvester.rank.store;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 站点账号体系持久层（用户 / 会话 / 邀请码），走 DuckDB 第三连接（meta，低频小写）。
 *
 * <ul>
 *   <li>{@code site_user}：注册用户。role ∈ USER / ADMIN；管理员只能凭邀请码注册。</li>
 *   <li>{@code site_session}：登录会话（持久化，重启不掉线）。</li>
 *   <li>{@code site_invite}：管理员邀请码（一次性、可撤销、可过期）。</li>
 * </ul>
 */
@Repository
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    private final Connection conn;

    public UserStore(@Qualifier("duckDbMetaConnection") Connection conn) {
        this.conn = conn;
    }

    @PostConstruct
    public void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SEQUENCE IF NOT EXISTS site_user_id_seq");
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS site_user(
                            id             BIGINT PRIMARY KEY,
                            username       VARCHAR,
                            username_lower VARCHAR,
                            password_hash  VARCHAR,
                            role           VARCHAR,
                            invited_by     VARCHAR,
                            created_at     BIGINT,
                            last_login_at  BIGINT,
                            disabled       BOOLEAN
                        )""");
                // 邮箱 + 邮箱验证（留言等操作要求已验证邮箱）
                st.execute("ALTER TABLE site_user ADD COLUMN IF NOT EXISTS email VARCHAR");
                st.execute("ALTER TABLE site_user ADD COLUMN IF NOT EXISTS email_verified BOOLEAN DEFAULT FALSE");
                st.execute("ALTER TABLE site_user ADD COLUMN IF NOT EXISTS verify_token VARCHAR");
                // 找回密码：一次性重置 token + 过期时刻
                st.execute("ALTER TABLE site_user ADD COLUMN IF NOT EXISTS reset_token VARCHAR");
                st.execute("ALTER TABLE site_user ADD COLUMN IF NOT EXISTS reset_expires BIGINT DEFAULT 0");
                // 用户名唯一（防并发重复注册）；老库若已有重复会建索引失败，忽略不阻断启动
                try {
                    st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_site_user_uname ON site_user(username_lower)");
                } catch (SQLException dup) {
                    log.warn("site_user username_lower 唯一索引创建失败（可能存在历史重复）：{}", dup.getMessage());
                }
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS site_session(
                            token      VARCHAR PRIMARY KEY,
                            user_id    BIGINT,
                            created_at BIGINT,
                            expires_at BIGINT,
                            ip         VARCHAR
                        )""");
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS site_invite(
                            code       VARCHAR PRIMARY KEY,
                            role       VARCHAR,
                            note       VARCHAR,
                            created_by VARCHAR,
                            created_at BIGINT,
                            expires_at BIGINT,
                            used_by    VARCHAR,
                            used_at    BIGINT,
                            revoked    BOOLEAN
                        )""");
            }
        }
        log.info("DuckDB site_user / site_session / site_invite 表已就绪");
    }

    /** 用户。role：USER / ADMIN。 */
    public record User(long id, String username, String passwordHash, String role,
                       String invitedBy, long createdAt, long lastLoginAt, boolean disabled,
                       String email, boolean emailVerified) {}

    /** 会话关联到的用户视图。 */
    public record SessionUser(String token, long expiresAt, long userId, String username, String role,
                              boolean disabled, String email, boolean emailVerified) {}

    /** 邀请码。 */
    public record Invite(String code, String role, String note, String createdBy, long createdAt,
                         long expiresAt, String usedBy, long usedAt, boolean revoked) {}

    // ── 用户 ─────────────────────────────────────────────────────────────────

    /** 字段列表（含 email/email_verified），所有 SELECT 共用以保证 readUser 列序一致。 */
    private static final String USER_COLS =
            "id, username, password_hash, role, invited_by, created_at, last_login_at, disabled, email,"
                    + " COALESCE(email_verified, FALSE)";

    /**
     * 写入新用户。@return 新用户 id；用户名唯一索引冲突等返回 -1（调用方据此提示「用户名已被占用」）。
     */
    public long createUser(String username, String passwordHash, String role, String invitedBy,
            String email, String verifyToken, long now) {
        synchronized (conn) {
            try {
                long id;
                try (var ps = conn.prepareStatement("SELECT nextval('site_user_id_seq')");
                        var rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
                try (var ps = conn.prepareStatement(
                        """
                        INSERT INTO site_user(id, username, username_lower, password_hash, role, invited_by,
                                              created_at, last_login_at, disabled, email, email_verified, verify_token)
                        VALUES(?,?,?,?,?,?,?,0,FALSE,?,FALSE,?)""")) {
                    ps.setLong(1, id);
                    ps.setString(2, username);
                    ps.setString(3, username.toLowerCase());
                    ps.setString(4, passwordHash);
                    ps.setString(5, role);
                    ps.setString(6, invitedBy);
                    ps.setLong(7, now);
                    ps.setString(8, email);
                    ps.setString(9, verifyToken);
                    ps.executeUpdate();
                }
                return id;
            } catch (SQLException e) {
                // 唯一索引冲突（并发同名注册）→ 返回 -1，由上层友好提示
                log.warn("写入 site_user 失败（可能用户名冲突）：{}", e.getMessage());
                return -1L;
            }
        }
    }

    public User findByUsername(String username) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT " + USER_COLS + " FROM site_user WHERE username_lower=?")) {
                ps.setString(1, username.toLowerCase());
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? readUser(rs) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_user 失败", e);
            }
        }
    }

    public User findById(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT " + USER_COLS + " FROM site_user WHERE id=?")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? readUser(rs) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_user 失败", e);
            }
        }
    }

    public List<User> listUsers() {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT " + USER_COLS + " FROM site_user ORDER BY id");
                    var rs = ps.executeQuery()) {
                var out = new ArrayList<User>();
                while (rs.next()) {
                    out.add(readUser(rs));
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_user 失败", e);
            }
        }
    }

    public boolean touchLogin(long userId, long now) {
        return exec("UPDATE site_user SET last_login_at=? WHERE id=?", ps -> {
            ps.setLong(1, now);
            ps.setLong(2, userId);
        });
    }

    public boolean setDisabled(long userId, boolean disabled) {
        return exec("UPDATE site_user SET disabled=? WHERE id=?", ps -> {
            ps.setBoolean(1, disabled);
            ps.setLong(2, userId);
        });
    }

    /** 更新口令哈希（用户自助改密码）。 */
    public boolean updatePassword(long userId, String passwordHash) {
        return exec("UPDATE site_user SET password_hash=? WHERE id=?", ps -> {
            ps.setString(1, passwordHash);
            ps.setLong(2, userId);
        });
    }

    /** 更换邮箱：写新邮箱 + 置为未验证 + 签发新验证 token（需重新走邮箱验证）。 */
    public boolean updateEmail(long userId, String email, String verifyToken) {
        return exec("UPDATE site_user SET email=?, email_verified=FALSE, verify_token=? WHERE id=?", ps -> {
            ps.setString(1, email);
            ps.setString(2, verifyToken);
            ps.setLong(3, userId);
        });
    }

    /** 凭验证 token 标记邮箱已验证。@return 是否命中（无效/已用 token 返回 false）。 */
    public boolean verifyEmail(String token) {
        return exec("UPDATE site_user SET email_verified=TRUE, verify_token=NULL WHERE verify_token=?",
                ps -> ps.setString(1, token));
    }

    /** 重新签发验证 token（重发验证邮件用）。 */
    public boolean setVerifyToken(long userId, String token) {
        return exec("UPDATE site_user SET verify_token=? WHERE id=?", ps -> {
            ps.setString(1, token);
            ps.setLong(2, userId);
        });
    }

    /** 签发找回密码 token（带过期时刻）。 */
    public boolean setResetToken(long userId, String token, long expiresAt) {
        return exec("UPDATE site_user SET reset_token=?, reset_expires=? WHERE id=?", ps -> {
            ps.setString(1, token);
            ps.setLong(2, expiresAt);
            ps.setLong(3, userId);
        });
    }

    /** 凭未过期的重置 token 找用户；无效/过期返回 null。 */
    public User findByResetToken(String token, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT " + USER_COLS + " FROM site_user WHERE reset_token=? AND reset_expires > ?")) {
                ps.setString(1, token);
                ps.setLong(2, now);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? readUser(rs) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_user 失败", e);
            }
        }
    }

    /** 清除重置 token（重置成功后一次性作废）。 */
    public boolean clearResetToken(long userId) {
        return exec("UPDATE site_user SET reset_token=NULL, reset_expires=0 WHERE id=?",
                ps -> ps.setLong(1, userId));
    }

    private static User readUser(ResultSet rs) throws SQLException {
        return new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getBoolean(8),
                rs.getString(9), rs.getBoolean(10));
    }

    // ── 会话 ─────────────────────────────────────────────────────────────────

    public void createSession(String token, long userId, long now, long expiresAt, String ip) {
        exec("INSERT INTO site_session(token, user_id, created_at, expires_at, ip) VALUES(?,?,?,?,?)", ps -> {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setLong(3, now);
            ps.setLong(4, expiresAt);
            ps.setString(5, ip);
        });
    }

    /** 查会话并连出用户；过期/不存在返回 null（过期记录由 {@link #purgeExpired} 周期清理）。 */
    public SessionUser findSession(String token, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    SELECT s.token, s.expires_at, u.id, u.username, u.role, u.disabled,
                           u.email, COALESCE(u.email_verified, FALSE)
                    FROM site_session s JOIN site_user u ON u.id = s.user_id
                    WHERE s.token=? AND s.expires_at > ?""")) {
                ps.setString(1, token);
                ps.setLong(2, now);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new SessionUser(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5), rs.getBoolean(6),
                            rs.getString(7), rs.getBoolean(8));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_session 失败", e);
            }
        }
    }

    public boolean deleteSession(String token) {
        return exec("DELETE FROM site_session WHERE token=?", ps -> ps.setString(1, token));
    }

    /** 删除某用户除当前会话外的所有会话（改密码后踢掉其他登录设备）。 */
    public boolean deleteSessionsExcept(long userId, String keepToken) {
        return exec("DELETE FROM site_session WHERE user_id=? AND token<>?", ps -> {
            ps.setLong(1, userId);
            ps.setString(2, keepToken == null ? "" : keepToken);
        });
    }

    public void purgeExpired(long now) {
        exec("DELETE FROM site_session WHERE expires_at <= ?", ps -> ps.setLong(1, now));
    }

    // ── 邀请码 ────────────────────────────────────────────────────────────────

    public void createInvite(String code, String role, String note, String createdBy, long now, long expiresAt) {
        exec(
                """
                INSERT INTO site_invite(code, role, note, created_by, created_at, expires_at, used_by, used_at, revoked)
                VALUES(?,?,?,?,?,?,NULL,0,FALSE)""",
                ps -> {
                    ps.setString(1, code);
                    ps.setString(2, role);
                    ps.setString(3, note);
                    ps.setString(4, createdBy);
                    ps.setLong(5, now);
                    ps.setLong(6, expiresAt);
                });
    }

    public Invite findInvite(String code) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT code, role, note, created_by, created_at, expires_at, used_by, used_at, revoked"
                            + " FROM site_invite WHERE code=?")) {
                ps.setString(1, code);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? readInvite(rs) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_invite 失败", e);
            }
        }
    }

    public List<Invite> listInvites() {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT code, role, note, created_by, created_at, expires_at, used_by, used_at, revoked"
                            + " FROM site_invite ORDER BY created_at DESC");
                    var rs = ps.executeQuery()) {
                var out = new ArrayList<Invite>();
                while (rs.next()) {
                    out.add(readInvite(rs));
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_invite 失败", e);
            }
        }
    }

    /** 标记邀请码已用（一次性）。@return 是否成功占用（false=已被并发用掉/撤销） */
    public boolean useInvite(String code, String usedBy, long now) {
        return exec(
                "UPDATE site_invite SET used_by=?, used_at=? WHERE code=? AND used_by IS NULL AND NOT revoked",
                ps -> {
                    ps.setString(1, usedBy);
                    ps.setLong(2, now);
                    ps.setString(3, code);
                });
    }

    public boolean revokeInvite(String code) {
        return exec("UPDATE site_invite SET revoked=TRUE WHERE code=?", ps -> ps.setString(1, code));
    }

    private static Invite readInvite(ResultSet rs) throws SQLException {
        return new Invite(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getLong(8), rs.getBoolean(9));
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private boolean exec(String sql, Binder binder) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("更新账号体系表失败", e);
            }
        }
    }
}
