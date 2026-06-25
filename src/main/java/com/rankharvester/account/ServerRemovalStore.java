package com.rankharvester.account;

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
 * 「因封号(rc=4)被移除的大区」记录表（{@code account_server_removal}，DuckDB 元数据连接持久化）。
 *
 * <p>登录失败转移遇到 rc=4 把某区从账号 {@code servers} 移除时，这里同步记下 {@code (account_id, server, removed_at)}；
 * {@link ServerRecheckService} 每 7 天复检一次：能登上(封号到期)就把该区加回账号并删除本记录；仍封号则 {@link #touch}
 * 刷新 {@code removed_at}，再等 7 天。封号有时限，故被封区终将自动恢复，无需人工。
 *
 * <p>用 {@code duckDbMetaConnection}（与 api_key / proxy_node 同一条元数据连接），避开抓榜主连接写入串行化死锁。
 */
@Repository
public class ServerRemovalStore {

    private static final Logger log = LoggerFactory.getLogger(ServerRemovalStore.class);

    private final Connection conn;

    public ServerRemovalStore(@Qualifier("duckDbMetaConnection") Connection duckDbMetaConnection) {
        this.conn = duckDbMetaConnection;
    }

    /** 一条被移除大区记录。{@code server} 为登录 id（大区号）字符串。 */
    public record Row(long accountId, String server, long removedAt) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS account_server_removal(
                            account_id  BIGINT NOT NULL,
                            server      VARCHAR NOT NULL,
                            removed_at  BIGINT,
                            PRIMARY KEY(account_id, server)
                        )""");
            } catch (SQLException e) {
                throw new IllegalStateException("建 account_server_removal 表失败", e);
            }
        }
        log.info("account_server_removal 表就绪（{} 条待复检记录）", all().size());
    }

    /** 记一条「因封号被移除」（首次移除时间为准；已存在则保留原 removed_at 不覆盖）。 */
    public void record(long accountId, String server, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO account_server_removal(account_id,server,removed_at) VALUES(?,?,?)"
                    + " ON CONFLICT(account_id,server) DO NOTHING")) {
                ps.setLong(1, accountId);
                ps.setString(2, server);
                ps.setLong(3, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("记录被移除大区失败 {}@{}: {}", accountId, server, e.getMessage());
            }
        }
    }

    /** 仍封号：刷新 removed_at，重置 7 天冷却。 */
    public void touch(long accountId, String server, long now) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE account_server_removal SET removed_at=? WHERE account_id=? AND server=?")) {
                ps.setLong(1, now);
                ps.setLong(2, accountId);
                ps.setString(3, server);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("刷新被移除大区时间失败 {}@{}: {}", accountId, server, e.getMessage());
            }
        }
    }

    /** 解封并加回后：删除该记录。 */
    public void delete(long accountId, String server) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM account_server_removal WHERE account_id=? AND server=?")) {
                ps.setLong(1, accountId);
                ps.setString(2, server);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("删除被移除大区记录失败 {}@{}: {}", accountId, server, e.getMessage());
            }
        }
    }

    /** {@code removed_at <= cutoff} 的记录（到了复检时机）。 */
    public List<Row> dueBefore(long cutoff) {
        var out = new ArrayList<Row>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT account_id,server,removed_at FROM account_server_removal"
                    + " WHERE removed_at <= ? ORDER BY removed_at")) {
                ps.setLong(1, cutoff);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getLong(1), rs.getString(2), rs.getLong(3)));
                    }
                }
            } catch (SQLException e) {
                log.warn("读取待复检大区失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 全部记录（建表日志/诊断用）。 */
    public List<Row> all() {
        return dueBefore(Long.MAX_VALUE);
    }
}
