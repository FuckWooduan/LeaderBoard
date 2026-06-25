package com.rankharvester.account;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 账号注册表：把游戏账号载入内存供调度/抓取使用。
 *
 * <p>载入优先级：① DuckDB {@code game_account}（由 {@link GameAccountSeeder} 灌种子）→
 * ② 旧版 {@code config/accounts.json} → ③ 少量演示账号（保证 dry-run 开箱即跑）。
 * 账号密码 / pauth 仅用于登录握手，不进日志、不进快照。
 *
 * <p>构造期注入 {@link GameAccountSeeder} 仅为保证种子导入先于本注册表载入完成（Bean 初始化顺序）。
 */
@Component
public class AccountRegistry {

    private static final Logger log = LoggerFactory.getLogger(AccountRegistry.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final GameAccountStore store;
    private final String accountsPath;
    private final int demoCount;
    private final List<GameAccount> accounts = new ArrayList<>();

    public AccountRegistry(
            GameAccountStore store,
            GameAccountSeeder seeder, // 仅用于强制种子导入先完成
            @Value("${rankharvester.accounts.path:config/accounts.json}") String accountsPath,
            @Value("${rankharvester.accounts.demo-count:5}") int demoCount) {
        this.store = store;
        this.accountsPath = accountsPath;
        this.demoCount = demoCount;
    }

    @PostConstruct
    void load() {
        // ① DuckDB game_account（种子导入后）
        var seeded = store.all();
        if (!seeded.isEmpty()) {
            accounts.addAll(seeded);
            log.info("已从 game_account 载入 {} 个账号", seeded.size());
            return;
        }

        // ② 旧版 accounts.json
        var path = Path.of(accountsPath);
        if (Files.isRegularFile(path)) {
            try {
                var bytes = Files.readAllBytes(path);
                List<GameAccount> loaded = MAPPER.readValue(bytes, MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, GameAccount.class));
                accounts.addAll(loaded);
                log.info("已从 {} 加载 {} 个账号", path.toAbsolutePath(), accounts.size());
                return;
            } catch (Exception e) {
                log.error("加载账号文件失败 {}，回退演示账号: {}", path, e.toString());
            }
        }

        // ③ 演示账号
        for (int i = 1; i <= demoCount; i++) {
            accounts.add(new GameAccount("demo-" + i, "demo_user_" + i, "demo_pwd", List.of("s1")));
        }
        log.warn("未找到 game_account 数据与账号文件 {}，已生成 {} 个演示账号（dry-run）",
                path.toAbsolutePath(), demoCount);
    }

    public synchronized List<GameAccount> all() {
        return List.copyOf(accounts);
    }

    /** 后台增删改账号后重读库，替换内存表（运行期生效）。 */
    public synchronized void reload() {
        var fresh = store.all();
        accounts.clear();
        accounts.addAll(fresh);
        log.info("game_account 已热重载：{} 个账号", accounts.size());
    }

    /** 全开：把所有账号的可用区服设为同一份完整列表（内存 + DuckDB 同步）。返回账号数。 */
    public synchronized int openAllServers(List<String> allServers) {
        String json = "[" + String.join(", ", allServers) + "]";
        store.setAllServers(json);
        var fixed = List.copyOf(allServers);
        for (int i = 0; i < accounts.size(); i++) {
            var a = accounts.get(i);
            accounts.set(i, new GameAccount(
                    a.id(), a.account(), a.password(), fixed,
                    a.pauth(), a.uid(), a.enabled(), a.available()));
        }
        log.info("已全开区服：{} 个账号 servers={}", accounts.size(), json);
        return accounts.size();
    }

    /** 内存层移除某账号的一个大区（与 DB 持久化同步，避免失败转移再次选中）。 */
    public synchronized void removeServer(String accountId, String server) {
        for (int i = 0; i < accounts.size(); i++) {
            var a = accounts.get(i);
            if (a.id().equals(accountId)) {
                var servers = new ArrayList<>(a.servers());
                if (servers.remove(server)) {
                    accounts.set(i, new GameAccount(
                            a.id(), a.account(), a.password(), servers,
                            a.pauth(), a.uid(), a.enabled(), a.available()));
                }
                return;
            }
        }
    }

    /** 内存层给某账号加回一个大区（封号解除后复检调用，与 DB 持久化同步）。已含则不动。 */
    public synchronized void addServer(String accountId, String server) {
        for (int i = 0; i < accounts.size(); i++) {
            var a = accounts.get(i);
            if (a.id().equals(accountId)) {
                if (!a.servers().contains(server)) {
                    var servers = new ArrayList<>(a.servers());
                    servers.add(server);
                    accounts.set(i, new GameAccount(
                            a.id(), a.account(), a.password(), servers,
                            a.pauth(), a.uid(), a.enabled(), a.available()));
                }
                return;
            }
        }
    }
}
