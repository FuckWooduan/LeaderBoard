package com.rankharvester.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 嵌入式 DuckDB 装配。
 *
 * <p>单进程列式存储，零额外容器。持有一条进程级读写连接（访问由各 Store 同步保护）。
 * 数据文件默认落在容器挂载的 {@code data/rank.duckdb}，重启不丢。
 */
@Configuration
public class DuckDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DuckDbConfig.class);

    @org.springframework.context.annotation.Primary
    @Bean(destroyMethod = "close")
    public Connection duckDbConnection(@Value("${rankharvester.duckdb.path:data/rank.duckdb}") String path)
            throws SQLException {
        try {
            var file = Path.of(path).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // 加载驱动
            Class.forName("org.duckdb.DuckDBDriver");
            var conn = DriverManager.getConnection("jdbc:duckdb:" + file);
            // 禁用 top_n 优化器：DuckDB v1.5.3 的 `ORDER BY + LIMIT` Top-N 算子在 LIMIT>行数时会产生重复行
            // （实测同一 9 行表 ORDER BY rank LIMIT 100 返回 18 行）。禁用后退化为「全排序+LIMIT」，结果正确。
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA disabled_optimizers='top_n'");
                // 关键：限制 DuckDB 堆外内存。DuckDB 默认 memory_limit=检测内存的 80%，容器 network_mode:host
                // 下它看到的是宿主 30GiB → 放开到 ~24GiB，抓榜大量写入(replacePartition DELETE+INSERT、
                // 主榜 1 万行物化)时无节制分配 ~GB 级缓冲，撑爆 8GiB cgroup → java 进程每 10 分钟被 OOM 杀。
                // 限到 2GiB：超出部分 DuckDB 自动溢写到磁盘(temp_directory 默认在 db 文件旁的挂载卷)，不再 OOM。
                // 三条连接均为本连接 .duplicate()、共享同一实例，此处设一次全局生效。
                st.execute("PRAGMA memory_limit='2GB'");
            }
            log.info("DuckDB 已打开: {}（禁用 top_n 优化器 + memory_limit=2GB 防容器 OOM）", file);
            return conn;
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB 驱动加载失败", e);
        } catch (Exception e) {
            throw new SQLException("DuckDB 初始化失败: " + path, e);
        }
    }

    /**
     * 分区获取专用的<b>第二条连接</b>（duplicate，共享同一 DuckDB 实例、MVCC 并发）。
     * 分区抓取写量大，独占此连接的锁，避免与主连接上的玩家查询/榜单读互相饿死。
     */
    @Bean(name = "duckDbSliceConnection", destroyMethod = "close")
    public Connection duckDbSliceConnection(Connection duckDbConnection) throws SQLException {
        var dup = ((org.duckdb.DuckDBConnection) duckDbConnection).duplicate();
        log.info("DuckDB 第二连接(分区获取专用)已建立");
        return dup;
    }

    /**
     * 轻量管理/元数据表专用的<b>第三条连接</b>（duplicate）：api_key、活动订阅邮箱等低频小写。
     * 单独一条连接、单独的锁，<b>不</b>与分区抓取(第二连接)的大量写入抢同一把锁——否则分区抓取忙时
     * 后台建 key / 读订阅会卡死(实测出现 499 客户端超时)。
     */
    @Bean(name = "duckDbMetaConnection", destroyMethod = "close")
    public Connection duckDbMetaConnection(Connection duckDbConnection) throws SQLException {
        var dup = ((org.duckdb.DuckDBConnection) duckDbConnection).duplicate();
        log.info("DuckDB 第三连接(管理/元数据专用)已建立");
        return dup;
    }
}
