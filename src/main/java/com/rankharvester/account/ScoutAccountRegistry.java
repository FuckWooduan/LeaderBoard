package com.rankharvester.account;

import com.rankharvester.net.GameServer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「频道大厅」侦察号注册表 —— 与抓榜账号池<strong>完全隔离</strong>。
 *
 * <p>从 DuckDB {@link ScoutAccountStore}（{@code scout_account} 表）载入侦察号，
 * <strong>不进</strong> {@code game_account} / {@link AccountRegistry} / {@code GameLoginService}，
 * 故调度器与玩家查询永远挑不到它们 → 不会被抓榜占用、不会与抓榜双登互踢。
 * 仅供频道/房间侦察子系统使用。登录复用与抓榜相同的 {@code pauth → flashvars → loginV3}。
 *
 * <p><b>一号多区</b>：一条侦察号的 {@code districts}（对外区号列表）下，同一个 {@link GameAccount} 实例
 * 会进入它<strong>每个区</strong>的池子；{@code servers} 装它所有区的登录号。pauth+serverId 机制下一个号能
 * 同时登多个区、多区同时驻留不互踢，故各区调度器独立用它即可（无需跨区协调）。
 *
 * <p>后台增删改后调用 {@link #reload()} 热生效，无需重启。
 */
@Component
public class ScoutAccountRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScoutAccountRegistry.class);

    private final ScoutAccountStore store;
    /** districtId（对外区号）→ 该区侦察号（可多个，热门区并发用）。 */
    private final Map<String, List<GameAccount>> byDistrict = new LinkedHashMap<>();

    /** 手填等级的预置种子（免懒登）：accountId + 该号区列表 + 等级。 */
    private final List<LevelSeed> levelSeeds = new ArrayList<>();

    /** 一条手填等级种子。{@code level} 适用于该号的<strong>每个区</strong>（按区灌进 ScoutLevelStore）。 */
    public record LevelSeed(String accountId, List<String> districts, int level) {}

    public ScoutAccountRegistry(ScoutAccountStore store) {
        this.store = store;
    }

    @PostConstruct
    public synchronized void reload() {
        byDistrict.clear();
        levelSeeds.clear();
        int n = 0;
        for (ScoutAccountStore.Row row : store.all()) {
            if (!row.enabled()) {
                continue;
            }
            String acctId = "scout-" + row.id();
            // 该号所有区 → 登录号列表（跳过未知区号）；同一实例入它每个区的池
            var loginIds = new ArrayList<String>();
            var districts = new ArrayList<String>();
            for (String d : row.districts()) {
                String district = d == null ? null : d.trim();
                if (district == null || district.isEmpty()) {
                    continue;
                }
                String loginId = GameServer.loginIdOfDistrict(district);
                if (loginId == null) {
                    log.warn("侦察号 {} 的区号 {} 不是已知对外区号，跳过该区", acctId, district);
                    continue;
                }
                loginIds.add(loginId);
                districts.add(district);
            }
            if (loginIds.isEmpty()) {
                // 未填区（或区全无效）：不进任何池，等后台「探测可用区」填充。不是错误。
                log.debug("侦察号 {}（account={}）暂无可用区，待探测", acctId, row.account());
                continue;
            }
            var acct = new GameAccount(
                    acctId, row.account(), row.password(), List.copyOf(loginIds),
                    blankToNull(row.pauth()), blankToNull(row.uid()), true, true);
            for (String district : districts) {
                byDistrict.computeIfAbsent(district, k -> new ArrayList<>()).add(acct);
            }
            if (row.level() != null && row.level() >= 0) {
                levelSeeds.add(new LevelSeed(acctId, List.copyOf(districts), row.level())); // 手填等级→每区免懒登
            }
            n++;
        }
        log.info("已载入 {} 个侦察号，覆盖 {} 个区: {}", n, byDistrict.size(), byDistrict.keySet());
    }

    /** 某区的侦察号（无则空表）。 */
    public synchronized List<GameAccount> forDistrict(String district) {
        return List.copyOf(byDistrict.getOrDefault(district, List.of()));
    }

    /** 该区是否配了侦察号。 */
    public synchronized boolean has(String district) {
        var list = byDistrict.get(district);
        return list != null && !list.isEmpty();
    }

    /** 所有配了侦察号的区（对外区号）。 */
    public synchronized List<String> districts() {
        return List.copyOf(byDistrict.keySet());
    }

    /** 任一带 pauth 的侦察号（名称配置解析回退用：抓榜池无 pauth 时借侦察号的 pauth 拉游戏配置）。无则 null。 */
    public synchronized GameAccount anyWithPauth() {
        for (var list : byDistrict.values()) {
            for (var a : list) {
                if (a.pauth() != null && !a.pauth().isBlank() && !a.servers().isEmpty()) {
                    return a;
                }
            }
        }
        return null;
    }

    /** 手填等级种子（每号区列表+等级）；用于按区免懒登预置 {@link ScoutLevelStore}。 */
    public synchronized List<LevelSeed> configLevelSeeds() {
        return List.copyOf(levelSeeds);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
