package com.rankharvester.weapon;

import com.rankharvester.gamedata.GameDataFetcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 武器库同步：抓取并解密 GameData → 解析 {@code shop_weapon} + {@code shop_weapon_property}(面板) → 全量替换 weapon 表。
 *
 * <p>启动时若库为空则异步同步一次；另提供手动触发。GameData 下载较慢(数十秒)，始终在后台线程执行、避免阻塞。
 */
@Service
public class WeaponSyncService {

    private static final Logger log = LoggerFactory.getLogger(WeaponSyncService.class);

    /** type:subType → 中文类型名。 */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "1:1", "步枪", "1:2", "狙击/弩", "1:3", "冲锋枪", "1:4", "机枪",
            "1:5", "霰弹枪", "1:6", "手枪", "1:7", "近战", "1:8", "投掷");

    /** 武器相关辅助表 → 可搜索条目（按结构定字段；字段含 HTML）。 */
    private record AuxTable(String table, String catLabel, String[] nameFields, String[] detailFields) {}

    private static final List<AuxTable> AUX_TABLES = List.of(
            new AuxTable("rpg_weapon_skill", "武器技能", new String[]{"name"}, new String[]{"comment", "description"}),
            new AuxTable("shop_weapon_slot_position", "配件槽位", new String[]{"name"}, new String[]{}),
            new AuxTable("shop_weapon_slot", "配件", new String[]{"name", "weaponName"}, new String[]{"description", "specific"}),
            new AuxTable("shop_weapon_slot_skill", "配件技能", new String[]{"name"}, new String[]{"des"}),
            new AuxTable("shop_weapon_slot_reform_affix", "重构词缀", new String[]{"name"}, new String[]{"desc"}));

    private final GameDataFetcher fetcher;
    private final WeaponStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weapon-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public WeaponSyncService(GameDataFetcher fetcher, WeaponStore store) {
        this.fetcher = fetcher;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        long n = store.count();
        if (n > 0) {
            log.info("[Weapon] 武器库已有 {} 把，跳过启动同步", n);
            return;
        }
        log.info("[Weapon] 武器库为空，启动后台同步...");
        syncAsync();
    }

    /** GameData 每天晚上 6 点更新；18:10 定时重新同步武器库（含新武器检测）。 */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 10 18 * * *", zone = "Asia/Shanghai")
    void scheduledDaily() {
        log.info("[Weapon] 每日 18:10 定时同步触发");
        syncAsync();
    }

    /** 后台触发一次同步（已在同步中则忽略）。 */
    public boolean syncAsync() {
        if (syncing.get()) {
            return false;
        }
        worker.submit(() -> {
            try {
                syncNow();
            } catch (RuntimeException e) {
                log.error("[Weapon] 武器库同步失败: {}", e.getMessage(), e);
            }
        });
        return true;
    }

    /** 同步执行一次（抓取 + 解析 + 全量替换）。返回写入武器数。 */
    public int syncNow() {
        if (!syncing.compareAndSet(false, true)) {
            log.info("[Weapon] 已有同步在进行，跳过");
            return -1;
        }
        try {
            log.info("[Weapon] 开始同步武器库（下载 GameData...）");
            var gd = fetcher.fetch();
            JsonNode weapons = GameDataFetcher.shopWeapon(gd);
            if (weapons == null || weapons.isEmpty()) {
                log.warn("[Weapon] GameData 中未找到 shop_weapon");
                return 0;
            }
            JsonNode props = findTable(gd, "shop_weapon_property");
            var rows = new ArrayList<Weapon>(weapons.size());
            for (var e : weapons.properties()) {
                JsonNode w = e.getValue();
                long id = w.path("id").asLong(parseLong(e.getKey()));
                String propId = firstText(w, "propertyId");
                if (propId == null) propId = e.getKey();
                JsonNode panel = props != null ? props.get(propId) : null;
                int type = w.path("type").asInt(0);
                int subType = w.path("subType").asInt(0);
                rows.add(new Weapon(
                        id, firstText(w, "name"), firstText(w, "codeName"), type, subType, typeLabel(type, subType),
                        firstText(w, "description"), firstText(w, "specific"), firstText(w, "tags"), firstText(w, "icon"),
                        pd(panel, "penetrate"), pd(panel, "limitCycle"), pd(panel, "power"), pd(panel, "accurate"),
                        pd(panel, "stability"), pd(panel, "weight"), pd(panel, "bullet"), pd(panel, "bulletMax")));
            }
            long now = System.currentTimeMillis();
            store.replaceAll(rows, now);

            // 辅助表（武器技能/配件/词缀等）→ 通用可搜索条目
            var entries = new ArrayList<WeaponStore.Entry>();
            for (AuxTable a : AUX_TABLES) {
                JsonNode t = findTable(gd, a.table());
                if (t == null) continue;
                int n = 0;
                for (var e : t.properties()) {
                    JsonNode node = e.getValue();
                    String name = firstTextOf(node, a.nameFields());
                    if (name == null) continue; // 无名条目跳过
                    String detail = firstTextOf(node, a.detailFields());
                    entries.add(new WeaponStore.Entry(a.table(), a.catLabel(), e.getKey(), name, detail, allStrings(node), node.toString()));
                    n++;
                }
                log.info("[Weapon] {} 提取 {} 条", a.catLabel(), n);
            }

            // shop_weapon_property：按 commentName 可搜（含「姜维之手（测试武器）」等不在 shop_weapon 的），
            // 去重已收录的武器名，detail 展示面板（穿透/射速/威力…）。
            if (props != null) {
                var weaponNames = new java.util.HashSet<String>();
                for (Weapon w : rows) {
                    if (w.name() != null) weaponNames.add(w.name());
                }
                int n = 0;
                for (var e : props.properties()) {
                    JsonNode node = e.getValue();
                    String name = firstText(node, "commentName");
                    if (name == null || weaponNames.contains(name)) continue;
                    entries.add(new WeaponStore.Entry("shop_weapon_property", "武器属性", e.getKey(), name, panelText(node), allStrings(node), node.toString()));
                    n++;
                }
                log.info("[Weapon] 武器属性(额外) 提取 {} 条", n);
            }
            store.replaceEntries(entries, now);

            log.info("[Weapon] 同步完成：{} 把武器 + {} 条辅助条目（面板 {}）",
                    rows.size(), entries.size(), props == null ? 0 : props.size());
            return rows.size();
        } finally {
            syncing.set(false);
        }
    }

    private static String typeLabel(int type, int subType) {
        return TYPE_LABELS.getOrDefault(type + ":" + subType, type + "型");
    }

    private static JsonNode findTable(GameDataFetcher.GameData gd, String name) {
        for (JsonNode c : new JsonNode[]{gd.gameData(), gd.gameDataOther()}) {
            if (c == null) continue;
            JsonNode n = c.get(name);
            if (n != null && n.isObject() && !n.isEmpty()) return n;
        }
        return null;
    }

    private static String firstTextOf(JsonNode node, String[] fields) {
        for (String f : fields) {
            String v = firstText(node, f);
            if (v != null) return v;
        }
        return null;
    }

    private static String firstText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }

    /** 该记录全部顶层字符串字段（去 HTML）拼接，供「全字符串模糊搜索」。 */
    private static String allStrings(JsonNode node) {
        var sb = new StringBuilder();
        for (var e : node.properties()) {
            JsonNode v = e.getValue();
            if (v != null && v.isTextual()) {
                String s = stripHtml(v.asString());
                if (!s.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }

    private static final String[][] PANEL_FIELDS = {
            {"penetrate", "穿透"}, {"limitCycle", "射速"}, {"power", "威力"}, {"accurate", "精准"},
            {"stability", "稳定"}, {"weight", "便携"}, {"bullet", "弹匣"}, {"bulletMax", "弹匣上限"}};

    /** shop_weapon_property 面板 → "穿透 X · 射速 Y · …" 文本。 */
    private static String panelText(JsonNode p) {
        var sb = new StringBuilder();
        for (String[] f : PANEL_FIELDS) {
            JsonNode v = p.get(f[0]);
            if (v != null && v.isNumber()) {
                if (sb.length() > 0) sb.append(" · ");
                double d = v.asDouble();
                sb.append(f[1]).append(' ').append(d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d));
            }
        }
        return sb.toString();
    }

    private static String stripHtml(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static Double pd(JsonNode panel, String field) {
        if (panel == null) return null;
        JsonNode v = panel.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
