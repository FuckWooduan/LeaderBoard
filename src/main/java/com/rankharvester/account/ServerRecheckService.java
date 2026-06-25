package com.rankharvester.account;

import com.rankharvester.net.GameLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 被封号(rc=4)移除大区的<strong>定期自动恢复</strong>：每天扫一遍 {@link ServerRemovalStore}，对「移除已满 7 天」的
 * 记录逐条复检——能登上(封号到期)就把该区加回账号并删记录；仍封号则刷新时间再等 7 天。封号有时限，故被封区终将自动恢复。
 *
 * <p>复检走 {@link GameLoginService#probeLogin}（熊猫住宅 IP，独立短连接，用完即关，不污染抓榜/查询连接池与失败转移状态）。
 * 每条之间节流，单轮上限 {@code recheck-max-per-run} 防突发登录；超出部分留到下一轮。
 */
@Service
public class ServerRecheckService {

    private static final Logger log = LoggerFactory.getLogger(ServerRecheckService.class);

    /** 被封区的复检冷却：移除满 7 天才复检；仍封则再等 7 天。 */
    private static final long RECHECK_AFTER_MS = 7L * 24 * 60 * 60_000L;
    /** 每条复检之间的节流间隔，避免一轮内突发大量登录。 */
    private static final long THROTTLE_MS = 3_000L;

    private final AccountRegistry accounts;
    private final GameAccountStore accountStore;
    private final ServerRemovalStore removalStore;
    private final GameLoginService loginService;
    private final int maxPerRun;

    public ServerRecheckService(AccountRegistry accounts, GameAccountStore accountStore,
            ServerRemovalStore removalStore, GameLoginService loginService,
            @Value("${rankharvester.recheck.max-per-run:300}") int maxPerRun) {
        this.accounts = accounts;
        this.accountStore = accountStore;
        this.removalStore = removalStore;
        this.loginService = loginService;
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    /** 启动 30 分钟后首跑，之后每 24 小时扫一次（实际只处理移除已满 7 天的记录）。 */
    @Scheduled(initialDelay = 30L * 60_000L, fixedDelay = 24L * 60 * 60_000L)
    void recheck() {
        long now = System.currentTimeMillis();
        var due = removalStore.dueBefore(now - RECHECK_AFTER_MS);
        if (due.isEmpty()) {
            return;
        }
        int total = due.size();
        int capped = Math.min(total, maxPerRun);
        log.info("[recheck] {} 条被封区到期复检（本轮处理 {}）", total, capped);
        int restored = 0, stillBanned = 0, transientFail = 0;
        for (int i = 0; i < capped; i++) {
            var r = due.get(i);
            GameAccount acct = accountById(String.valueOf(r.accountId()));
            if (acct == null || !acct.enabled()) {
                removalStore.delete(r.accountId(), r.server()); // 账号已删/停用：记录无意义
                continue;
            }
            if (acct.servers().contains(r.server())) {
                removalStore.delete(r.accountId(), r.server()); // 已在列表（如被"全开"加回）：清记录
                continue;
            }
            try {
                if (loginService.probeLogin(acct, r.server())) {
                    accountStore.addServer(r.accountId(), r.server());
                    accounts.addServer(acct.id(), r.server());
                    removalStore.delete(r.accountId(), r.server());
                    restored++;
                    log.info("[recheck] 账号 {} 大区 {} 封号已解除，已加回", r.accountId(), r.server());
                } else {
                    removalStore.touch(r.accountId(), r.server(), now); // 仍封号：重置 7 天冷却
                    stillBanned++;
                }
            } catch (RuntimeException e) {
                transientFail++;
                log.info("[recheck] 账号 {} 大区 {} 复检瞬时失败（{}），留待下次", r.accountId(), r.server(), e.getMessage());
            }
            sleepQuietly();
        }
        if (total > capped) {
            log.info("[recheck] 本轮达上限 {}，剩 {} 条留待下一轮", maxPerRun, total - capped);
        }
        log.info("[recheck] 完成：恢复 {} / 仍封 {} / 瞬时失败 {}", restored, stillBanned, transientFail);
    }

    private GameAccount accountById(String id) {
        return accounts.all().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
