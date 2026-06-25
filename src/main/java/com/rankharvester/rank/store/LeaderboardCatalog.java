package com.rankharvester.rank.store;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankKind;
import com.rankharvester.rank.model.RankType;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 排行榜目录（类型感知）。
 *
 * <p>榜是「大区维度」的实体，不按账号铺开：对每个出现过的大区，挑一个可用账号去抓，派生
 * 4 个 Reduced（同服经验 331 / 跨服经验 332 / 跨服战力 333 / 同服战力 382）+ 1 个 Ladder（天梯 rankId 23, category [4]）。
 * 请求参数严格照抓包原样、单发一次。
 *
 * <p><b>动态榜默认 enabled=false</b>（「抓到的动态榜默认过时」），由后台手动开启当前赛季；
 * 仅 dry-run 演示时默认全开，便于无后台时端到端跑通。分区静态榜将来由发现流程登记。
 */
@Component
public class LeaderboardCatalog {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardCatalog.class);

    private final AccountRegistry accounts;
    private final boolean dryRun;
    private final Map<String, LeaderboardDef> byCode = new LinkedHashMap<>();

    public LeaderboardCatalog(
            AccountRegistry accounts,
            @Value("${rankharvester.game.dry-run:true}") boolean dryRun) {
        this.accounts = accounts;
        this.dryRun = dryRun;
    }

    @PostConstruct
    void build() {
        // 每个大区挑一个 enabled 账号负责抓取
        Map<String, String> serverToAccount = new LinkedHashMap<>();
        for (var acct : accounts.all()) {
            if (!acct.enabled()) continue;
            for (var server : acct.servers()) {
                serverToAccount.putIfAbsent(server, acct.id());
            }
        }

        var serverList = new ArrayList<>(serverToAccount.entrySet());
        for (var e : serverToAccount.entrySet()) {
            String server = e.getKey();
            String accountId = e.getValue();
            // ── 按区(loginId) ──：同服战力 382、同服经验 331（每个区自己的榜，互不相同）。
            add(new LeaderboardDef("REDUCED:382:" + server, RankType.REDUCED, RankKind.POWER,
                    accountId, server, 0, List.of(382, 0, 1000, 0), true));
            add(new LeaderboardDef("REDUCED:331:" + server, RankType.REDUCED, RankKind.EXP,
                    accountId, server, 0, List.of(331, 0, 1000, 0), true));
            // 战队榜：getTeamList(rowOffset)，每页 8、带总数。每区自己的战队榜。每天刷新。
            add(new LeaderboardDef("TEAM:" + server, RankType.TEAM, RankKind.TEAM,
                    accountId, server, 0, List.of(0), true));
        }
        // ── 全服各一份 ──：跨服战力 333、跨服经验 332、天梯，全服共享同一份数据（实测各区一致），
        // 只用代表区登录抓一次，code 用 ":all"（避免按区重复抓 20 份）。
        // 分散到不同代表区：避免单区一条连接串行抓太多导致超时。
        if (!serverList.isEmpty()) {
            var s0 = serverList.get(0);
            var s1 = serverList.get(Math.min(1, serverList.size() - 1));
            var s2 = serverList.get(Math.min(2, serverList.size() - 1));
            add(new LeaderboardDef("REDUCED:333:all", RankType.REDUCED, RankKind.POWER,
                    s0.getValue(), s0.getKey(), 0, List.of(333, 0, 1000, 0), true));
            add(new LeaderboardDef("REDUCED:332:all", RankType.REDUCED, RankKind.EXP,
                    s1.getValue(), s1.getKey(), 0, List.of(332, 0, 1000, 0), true));
            add(new LeaderboardDef("LADDER:23:all", RankType.LADDER, RankKind.CURRENT_SEASON,
                    s2.getValue(), s2.getKey(), 0, List.of(23, List.of(4), 0, 50, 0), true));
        }
        log.info("排行榜目录已构建：{} 个榜（{} 个大区×同服382/331+战队 + 跨服333/332+天梯各1份全服；dryRun={}）",
                byCode.size(), serverToAccount.size(), dryRun);
    }

    private void add(LeaderboardDef def) {
        byCode.put(def.code(), def);
    }

    public List<LeaderboardDef> all() {
        return new ArrayList<>(byCode.values());
    }

    public Optional<LeaderboardDef> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Map<String, LeaderboardDef> asMap() {
        return Collections.unmodifiableMap(byCode);
    }
}
