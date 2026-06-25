package com.rankharvester.net;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * sing-box 出站代理节点（trojan）的 DuckDB 持久层（{@code proxy_node} 表）。后台可增删改查 + 启停，
 * 改完由 {@link SingboxConfigService#regenerateAndReload()} 重新生成 sing-box 配置并热重载，无需手改文件。
 *
 * <p>用 {@code duckDbMetaConnection}（与 API key / 侦察号同一条元数据连接），避开抓榜主连接的写入串行化死锁。
 *
 * <p>{@code security}=「none」表示裸 TCP trojan（无 TLS，对应 trojan 链接的 {@code security=none}），
 * 「tls」则启用 TLS（用 {@code sni} 作 server_name，{@code insecure} 跳过证书校验）。
 */
@Repository
public class ProxyNodeStore {

    private static final Logger log = LoggerFactory.getLogger(ProxyNodeStore.class);

    private final Connection conn;

    public ProxyNodeStore(@Qualifier("duckDbMetaConnection") Connection duckDbMetaConnection) {
        this.conn = duckDbMetaConnection;
    }

    /**
     * 一条出站节点。{@code type}=「trojan」|「anytls」（缺省 trojan，向后兼容旧库）。
     * {@code security}=「none」|「tls」；{@code sni}/{@code insecure} 仅 tls/anytls 用（anytls 恒 TLS）。
     */
    public record Row(long id, String type, String name, String server, int port, String password,
            String security, String sni, boolean insecure,
            boolean enabled, long createdAt, long updatedAt) {}

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SEQUENCE IF NOT EXISTS proxy_node_seq START 1");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS proxy_node(
                            id          BIGINT PRIMARY KEY,
                            name        VARCHAR,
                            server      VARCHAR NOT NULL,
                            port        INTEGER NOT NULL,
                            password    VARCHAR,
                            security    VARCHAR,
                            sni         VARCHAR,
                            insecure    BOOLEAN,
                            enabled     BOOLEAN,
                            created_at  BIGINT,
                            updated_at  BIGINT
                        )""");
                // 旧库迁移：补 type 列（默认 trojan，保持既有 trojan 节点不变）。
                st.execute("ALTER TABLE proxy_node ADD COLUMN IF NOT EXISTS type VARCHAR DEFAULT 'trojan'");
            } catch (SQLException e) {
                throw new IllegalStateException("建 proxy_node 表失败", e);
            }
        }
        log.info("proxy_node 表就绪（{} 个代理节点）", all().size());
    }

    public long create(String type, String name, String server, int port, String password,
            String security, String sni, boolean insecure, boolean enabled) {
        long now = System.currentTimeMillis();
        synchronized (conn) {
            try {
                long id;
                try (var rs = conn.createStatement().executeQuery("SELECT nextval('proxy_node_seq')")) {
                    rs.next();
                    id = rs.getLong(1);
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO proxy_node(id,type,name,server,port,password,security,sni,insecure,enabled,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, normalizeType(type));
                    ps.setString(3, name);
                    ps.setString(4, server);
                    ps.setInt(5, port);
                    ps.setString(6, password);
                    ps.setString(7, security);
                    ps.setString(8, sni);
                    ps.setBoolean(9, insecure);
                    ps.setBoolean(10, enabled);
                    ps.setLong(11, now);
                    ps.setLong(12, now);
                    ps.executeUpdate();
                }
                return id;
            } catch (SQLException e) {
                throw new IllegalStateException("创建代理节点失败", e);
            }
        }
    }

    public void update(long id, String type, String name, String server, int port, String password,
            String security, String sni, boolean insecure, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "UPDATE proxy_node SET type=?,name=?,server=?,port=?,password=?,security=?,sni=?,insecure=?,enabled=?,updated_at=?"
                    + " WHERE id=?")) {
                ps.setString(1, normalizeType(type));
                ps.setString(2, name);
                ps.setString(3, server);
                ps.setInt(4, port);
                ps.setString(5, password);
                ps.setString(6, security);
                ps.setString(7, sni);
                ps.setBoolean(8, insecure);
                ps.setBoolean(9, enabled);
                ps.setLong(10, System.currentTimeMillis());
                ps.setLong(11, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新代理节点失败", e);
            }
        }
    }

    public void delete(long id) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM proxy_node WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除代理节点失败", e);
            }
        }
    }

    public void setEnabled(long id, boolean enabled) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("UPDATE proxy_node SET enabled=?,updated_at=? WHERE id=?")) {
                ps.setBoolean(1, enabled);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("改代理节点状态失败", e);
            }
        }
    }

    public Optional<Row> get(long id) {
        return all().stream().filter(r -> r.id() == id).findFirst();
    }

    /** 所有节点（按 id 升序）。 */
    public List<Row> all() {
        var out = new ArrayList<Row>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT id,type,name,server,port,password,security,sni,insecure,enabled,created_at,updated_at"
                            + " FROM proxy_node ORDER BY id")) {
                while (rs.next()) {
                    out.add(new Row(rs.getLong(1), normalizeType(rs.getString(2)), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getBoolean(9), rs.getBoolean(10), rs.getLong(11), rs.getLong(12)));
                }
            } catch (SQLException e) {
                log.warn("读 proxy_node 列表失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 仅启用的节点（生成 sing-box 出站用）。 */
    public List<Row> enabledNodes() {
        return all().stream().filter(Row::enabled).toList();
    }

    /** 归一化节点类型：仅 trojan / anytls；空或未知一律按 trojan（向后兼容）。 */
    public static String normalizeType(String type) {
        return "anytls".equalsIgnoreCase(type == null ? null : type.trim()) ? "anytls" : "trojan";
    }
}
