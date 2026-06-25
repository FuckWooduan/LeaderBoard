package com.rankharvester.web;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.gamedata.GameDataFetcher;
import com.rankharvester.net.GameServer;
import com.rankharvester.weapon.WeaponStore;
import com.rankharvester.weapon.WeaponSyncService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * GameData / 武器功能诊断（admin 鉴权下，挂 /api/boards/** 受 AdminAuthFilter 保护）。
 */
@RestController
@RequestMapping("/api/boards")
public class GameDataController {

    private final GameDataFetcher fetcher;
    private final WeaponSyncService weaponSync;
    private final WeaponStore weaponStore;
    private final AccountRegistry accounts;

    public GameDataController(GameDataFetcher fetcher, WeaponSyncService weaponSync, WeaponStore weaponStore,
            AccountRegistry accounts) {
        this.fetcher = fetcher;
        this.weaponSync = weaponSync;
        this.weaponStore = weaponStore;
        this.accounts = accounts;
    }

    /** 全开区服：把所有账号的可用区服设为「所有已知大区(loginId)」。一次性，立即生效（含内存）。 */
    @PostMapping("/open-all-servers")
    public Map<String, Object> openAllServers() {
        List<String> ids = Arrays.stream(GameServer.values())
                .map(g -> String.valueOf(g.id()))
                .distinct()
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();
        int n = accounts.openAllServers(ids);
        return Map.of("updated", n, "servers", ids);
    }

    /** 手动触发武器库同步（后台异步执行）。 */
    @PostMapping("/weapon-sync")
    public Map<String, Object> weaponSync() {
        boolean triggered = weaponSync.syncAsync();
        return Map.of("triggered", triggered, "currentCount", weaponStore.count());
    }

    /** 武器库当前状态。 */
    @GetMapping("/weapon-status")
    public Map<String, Object> weaponStatus() {
        return Map.of("count", weaponStore.count());
    }

    /**
     * 验证：能否下载并解密出 GameData，找到 shop_weapon，给出样本字段结构。
     * 可传 {@code pauth}（用指定 pauth 测，库里账号可能过期）+ {@code server}（登录区，如 6）。
     */
    @GetMapping("/gamedata-test")
    public Map<String, Object> test(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String pauth,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String server,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String table) {
        var out = new LinkedHashMap<String, Object>();
        try {
            var gd = (pauth != null && !pauth.isBlank())
                    ? fetcher.fetch(pauth, server != null && !server.isBlank()
                            ? java.util.List.of(server) : null)
                    : fetcher.fetch();
            // 指定 table 时：在两个 container 里找该表，dump 前 2 条
            if (table != null && !table.isBlank()) {
                for (JsonNode c : new JsonNode[]{gd.gameData(), gd.gameDataOther()}) {
                    if (c == null) continue;
                    JsonNode t = c.get(table);
                    if (t != null && t.isObject() && !t.isEmpty()) {
                        out.put("table", table);
                        out.put("count", t.size());
                        var sample = new LinkedHashMap<String, Object>();
                        int n = 0;
                        for (var e : t.properties()) {
                            sample.put(e.getKey(), e.getValue().toString());
                            if (++n >= 2) break;
                        }
                        out.put("sample", sample);
                        return out;
                    }
                }
                out.put("table", table);
                out.put("found", false);
                return out;
            }
            out.put("gameData", gd.gameData() != null);
            out.put("gameDataOther", gd.gameDataOther() != null);
            if (gd.gameData() != null) out.put("gameDataTopKeys", topKeys(gd.gameData()));
            if (gd.gameDataOther() != null) out.put("gameDataOtherTopKeys", topKeys(gd.gameDataOther()));

            var sw = GameDataFetcher.shopWeapon(gd);
            out.put("shopWeaponCount", sw == null ? 0 : sw.size());
            if (sw != null && !sw.isEmpty()) {
                var first = sw.properties().iterator().next();
                out.put("sampleKey", first.getKey());
                var n = first.getValue();
                out.put("sampleName", n.path("name").asString(""));
                out.put("sampleCodeName", n.path("codeName").asString(""));
                out.put("sampleFields", fieldNames(n));
                out.put("sampleJson", n.toString().length() > 1500
                        ? n.toString().substring(0, 1500) + "…" : n.toString());
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private static java.util.List<String> topKeys(JsonNode container) {
        var keys = new ArrayList<String>();
        for (var e : container.properties()) {
            var node = e.getValue();
            int size = node.isObject() || node.isArray() ? node.size() : -1;
            keys.add(size >= 0 ? e.getKey() + "(" + size + ")" : e.getKey());
            if (keys.size() >= 60) break;
        }
        return keys;
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        var keys = new ArrayList<String>();
        for (var e : node.properties()) keys.add(e.getKey());
        return keys;
    }
}
