package com.rankharvester.account;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 游戏账号种子导入器。
 *
 * <p>启动时若配置了 {@code rankharvester.accounts.seed-sql}（StrikeGod 的 pg_dump 导出文件）
 * 且 {@code game_account} 表为空，则解析其 COPY 块并灌入 DuckDB。已有数据则跳过（幂等）。
 * 导出文件含明文密码 / pauth，属敏感文件，不入库到 git，仅按部署机本地路径读取。
 */
@Component
public class GameAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(GameAccountSeeder.class);

    private final GameAccountStore store;
    private final String seedSqlPath;

    public GameAccountSeeder(
            GameAccountStore store,
            @Value("${rankharvester.accounts.seed-sql:}") String seedSqlPath) {
        this.store = store;
        this.seedSqlPath = seedSqlPath;
    }

    @PostConstruct
    void seed() {
        if (seedSqlPath == null || seedSqlPath.isBlank()) {
            log.debug("未配置 rankharvester.accounts.seed-sql，跳过账号种子导入");
            return;
        }
        var path = Path.of(seedSqlPath);
        if (!Files.isRegularFile(path)) {
            log.warn("账号种子文件不存在，跳过导入: {}", path.toAbsolutePath());
            return;
        }
        long existing = store.count();
        if (existing > 0) {
            log.info("game_account 已有 {} 个账号，跳过种子导入", existing);
            return;
        }
        try {
            String text = Files.readString(path);
            var rows = PgGameAccountDump.parse(text);
            if (rows.isEmpty()) {
                log.warn("种子文件未解析出任何账号: {}", path.toAbsolutePath());
                return;
            }
            int n = store.insertBatch(rows);
            log.info("已从 {} 导入 {} 个游戏账号到 game_account", path.toAbsolutePath(), n);
        } catch (Exception e) {
            log.error("导入账号种子失败: {}", path.toAbsolutePath(), e);
        }
    }
}
