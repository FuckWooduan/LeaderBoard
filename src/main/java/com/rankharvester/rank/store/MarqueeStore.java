package com.rankharvester.rank.store;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 顶部走马灯广告（单条配置）持久层。
 *
 * <p>表 {@code site_marquee} 恒只一行（id=1）：
 * <ul>
 *   <li>{@code enabled} —— 是否在前台展示</li>
 *   <li>{@code lines_text} —— 多行文案（原始换行串，前台按行拆分横向滚动）</li>
 *   <li>{@code image_url} —— 末尾图片（data URL 或 http 链接，可空），前台点击放大</li>
 * </ul>
 */
@Repository
public class MarqueeStore {

    private static final Logger log = LoggerFactory.getLogger(MarqueeStore.class);

    private final Connection conn;

    public MarqueeStore(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    @PostConstruct
    public void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS site_marquee(
                            id         INTEGER PRIMARY KEY,
                            enabled    BOOLEAN,
                            lines_text VARCHAR,
                            image_url  VARCHAR,
                            updated_at BIGINT
                        )""");
            }
        }
        log.info("DuckDB site_marquee 表已就绪");
    }

    /** 走马灯配置（lines 为原始多行文本，含换行）。 */
    public record MarqueeConfig(boolean enabled, String lines, String image, long updatedAt) {}

    /** 读取当前配置；无记录时返回禁用空配置。 */
    public MarqueeConfig get() {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT enabled, lines_text, image_url, updated_at FROM site_marquee WHERE id=1");
                    var rs = ps.executeQuery()) {
                if (rs.next()) {
                    String lines = rs.getString(2);
                    String image = rs.getString(3);
                    return new MarqueeConfig(
                            rs.getBoolean(1),
                            lines == null ? "" : lines,
                            image == null ? "" : image,
                            rs.getLong(4));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 site_marquee 失败", e);
            }
        }
        return new MarqueeConfig(false, "", "", 0L);
    }

    /** 写入配置（恒覆盖 id=1 单行）。 */
    public void save(boolean enabled, String lines, String image, long updatedAt) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    """
                    INSERT INTO site_marquee(id, enabled, lines_text, image_url, updated_at) VALUES(1,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        enabled=excluded.enabled, lines_text=excluded.lines_text,
                        image_url=excluded.image_url, updated_at=excluded.updated_at""")) {
                ps.setBoolean(1, enabled);
                ps.setString(2, lines == null ? "" : lines);
                if (image == null || image.isBlank()) {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(3, image);
                }
                ps.setLong(4, updatedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入 site_marquee 失败", e);
            }
        }
    }
}
