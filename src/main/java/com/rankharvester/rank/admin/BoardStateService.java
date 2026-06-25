package com.rankharvester.rank.admin;

import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.store.BoardStateStore;
import com.rankharvester.rank.store.BoardStateStore.FieldOverride;
import com.rankharvester.rank.store.BoardStateStore.SortConfig;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 排行榜后台配置服务：启用(是否抓取) / 开放(是否对前端可见) / 字段显示名。
 *
 * <p>内存覆盖层 + DuckDB 持久化。动态榜默认不抓（目录默认值）、默认不开放（public 默认 false）。
 * 字段显示名：后台覆盖优先 → 内置默认中文名 → 字段键本身。
 */
@Service
public class BoardStateService {

    private static final Logger log = LoggerFactory.getLogger(BoardStateService.class);

    private final BoardStateStore store;
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();
    private final Map<String, Boolean> publicOverrides = new ConcurrentHashMap<>();
    // code -> (field -> 字段配置覆盖)
    private final Map<String, Map<String, FieldOverride>> fieldOverrides = new ConcurrentHashMap<>();
    // 榜组 group -> 排名依据多条件（有序，列表顺序=优先级）
    private final Map<String, List<SortConfig>> sortOverrides = new ConcurrentHashMap<>();

    public BoardStateService(BoardStateStore store) {
        this.store = store;
    }

    @PostConstruct
    void load() {
        enabledOverrides.putAll(store.loadEnabled());
        publicOverrides.putAll(store.loadPublic());
        store.loadFieldConfigs().forEach((code, m) -> fieldOverrides.put(code, new ConcurrentHashMap<>(m)));
        sortOverrides.putAll(store.loadSort());
        seedDefaults();
        seedSortDefaults();
        log.info("已载入后台配置：enabled 覆盖 {}、public 覆盖 {}、字段配置覆盖 {} 个榜组、排名依据 {} 个榜组",
                enabledOverrides.size(), publicOverrides.size(), fieldOverrides.size(), sortOverrides.size());
    }

    /**
     * 字段配置的「榜组」key —— 去掉区服段，使同一逻辑榜的所有区服共用一套配置。
     * {@code REDUCED:382:15 → REDUCED:382}、{@code LADDER:23:15 → LADDER:23}、{@code TEAM:15 → TEAM}。
     */
    static String groupKey(String code) {
        int i = code.lastIndexOf(':');
        return i < 0 ? code : code.substring(0, i);
    }

    /** 首次启动为已知榜组预置合理默认（关键列默认显示+命名+渲染），使榜开箱可用；已有配置的组不动。 */
    private void seedDefaults() {
        // 组 → [field, label, render]（按此顺序，visible=true）
        seedGroup("REDUCED:382", new String[][]{
            {"info_char_name", "角色名", "TEXT"}, {"info_lv", "等级", "NUMBER"},
            {"info_team_name", "战队", "TEXT"}, {"score_number", "战力", "NUMBER"},
            {"info_login_id", "区", "DISTRICT"}, {"score_rebirth", "境界", "REALM"}});
        seedGroup("REDUCED:331", new String[][]{
            {"info_char_name", "角色名", "TEXT"}, {"info_lv", "等级", "NUMBER"},
            {"info_team_name", "战队", "TEXT"}, {"score_number", "经验", "NUMBER"},
            {"info_login_id", "区", "DISTRICT"}, {"score_rebirth", "境界", "REALM"}});
        seedGroup("REDUCED:333", new String[][]{
            {"info_char_name", "角色名", "TEXT"}, {"info_lv", "等级", "NUMBER"},
            {"info_team_name", "战队", "TEXT"}, {"score_number", "战力", "NUMBER"},
            {"info_login_id", "区", "DISTRICT"}, {"score_rebirth", "境界", "REALM"}});
        seedGroup("REDUCED:332", new String[][]{
            {"info_char_name", "角色名", "TEXT"}, {"info_lv", "等级", "NUMBER"},
            {"info_team_name", "战队", "TEXT"}, {"score_number", "经验", "NUMBER"},
            {"info_login_id", "区", "DISTRICT"}, {"score_rebirth", "境界", "REALM"}});
        seedGroup("LADDER:23", new String[][]{
            {"char_name", "角色名", "TEXT"}, {"team_name", "战队", "TEXT"},
            {"lv", "等级", "NUMBER"}, {"number", "积分", "NUMBER"}});
        seedGroup("TEAM", new String[][]{
            {"team_name", "战队名", "TEXT"}, {"leader_name", "队长", "TEXT"},
            {"team_lv", "等级", "NUMBER"}, {"team_exp", "经验", "NUMBER"},
            {"rpg_team_total_bonus", "冒险贡献", "NUMBER"}, {"m_number", "人数", "NUMBER"},
            {"rpg_team_declaration", "宣言", "LONGTEXT"}});
    }

    private void seedGroup(String group, String[][] defs) {
        if (fieldOverrides.containsKey(group)) {
            return; // 已有配置，不覆盖
        }
        int order = 0;
        for (String[] d : defs) {
            setFieldConfigByGroup(group, d[0], d[1], order++, true, RenderType.valueOf(d[2]));
        }
    }

    /** 各榜组默认排名依据（字段, 方向）。未显式配置时用之，保证开箱即按合理字段排名。 */
    private static final Map<String, String[]> DEFAULT_SORT = Map.of(
            "REDUCED:382", new String[]{"score_number", "DESC"},
            "REDUCED:331", new String[]{"score_number", "DESC"},
            "REDUCED:333", new String[]{"score_number", "DESC"},
            "REDUCED:332", new String[]{"score_number", "DESC"},
            "LADDER:23", new String[]{"number", "DESC"});
    // 注：战队榜(TEAM)排序为特例——前台切换「普通(team_exp)/冒险(rpg_team_total_bonus)」，不走后台排名依据。

    private void seedSortDefaults() {
        DEFAULT_SORT.forEach((group, fd) -> {
            if (!sortOverrides.containsKey(group)) {
                setSortByGroup(group, List.of(new SortConfig(fd[0], fd[1])));
            }
        });
    }

    // ── 排名依据（多条件，按列表顺序为优先级；名次=排序后行号） ────────────────────────
    /**
     * 某榜有效排名依据多条件：后台配置（逐条校验字段属于该榜型、剔除非法）→ 为空则用默认 → 再兜底首个非 rank 字段。
     * 字段强制在白名单内（防注入），方向规整为 ASC/DESC。
     */
    public List<SortConfig> sortFor(String code, RankType type) {
        var fields = BoardFields.fieldsOf(type);
        String group = groupKey(code);
        List<SortConfig> ov = sortOverrides.get(group);
        var result = new ArrayList<SortConfig>();
        if (ov != null) {
            for (SortConfig s : ov) {
                if (s.field() != null && !"rank".equals(s.field()) && fields.contains(s.field())) {
                    result.add(new SortConfig(s.field(), "ASC".equalsIgnoreCase(s.dir()) ? "ASC" : "DESC"));
                }
            }
        }
        if (result.isEmpty()) {
            String[] dft = DEFAULT_SORT.get(group);
            String f = dft != null ? dft[0] : fields.stream().filter(x -> !"rank".equals(x)).findFirst().orElse("rank");
            String d = dft != null && "ASC".equalsIgnoreCase(dft[1]) ? "ASC" : "DESC";
            result.add(new SortConfig(f, d));
        }
        return result;
    }

    public void setSort(String code, List<SortConfig> sorts) {
        setSortByGroup(groupKey(code), sorts);
    }

    private void setSortByGroup(String group, List<SortConfig> sorts) {
        var norm = new ArrayList<SortConfig>();
        for (SortConfig s : sorts) {
            norm.add(new SortConfig(s.field(), "ASC".equalsIgnoreCase(s.dir()) ? "ASC" : "DESC"));
        }
        store.upsertSort(group, norm);
        sortOverrides.put(group, norm);
        log.info("后台排名依据 [{}] = {}", group, BoardStateStore.serializeSpec(norm));
    }

    // ── 是否抓取 ──────────────────────────────────────────────────────────────
    public boolean isEnabled(LeaderboardDef def) {
        Boolean ov = enabledOverrides.get(def.code());
        return ov != null ? ov : def.enabled();
    }

    public void setEnabled(String code, boolean enabled, long nowMs) {
        store.upsertEnabled(code, enabled, nowMs);
        enabledOverrides.put(code, enabled);
        log.info("后台{}抓取 {}", enabled ? "开启" : "关闭", code);
    }

    public boolean hasEnabledOverride(String code) {
        return enabledOverrides.containsKey(code);
    }

    // ── 是否对前端开放 ────────────────────────────────────────────────────────
    /** 默认不开放；后台显式开启后对前端可见。 */
    public boolean isPublic(String code) {
        return publicOverrides.getOrDefault(code, false);
    }

    /**
     * 后台是否对该榜做过显式开放/关闭设置（区分「未设置」与「显式关闭」）。
     * true 时应以 {@link #isPublic(String)} 为准（即使有数据，显式关闭也要隐藏）。
     */
    public boolean hasPublicOverride(String code) {
        return publicOverrides.containsKey(code);
    }

    public void setPublic(String code, boolean publicVisible, long nowMs) {
        store.upsertPublic(code, publicVisible, nowMs);
        publicOverrides.put(code, publicVisible);
        log.info("后台{}开放 {}", publicVisible ? "开启" : "关闭", code);
    }

    // ── 字段配置（显示名 / 列序 / 显隐 / 渲染） ──────────────────────────────────
    /** 单字段有效显示名：覆盖 → 默认中文 → 字段键。 */
    public String label(String code, String field) {
        var m = fieldOverrides.get(groupKey(code));
        if (m != null) {
            var ov = m.get(field);
            if (ov != null && ov.label() != null) {
                return ov.label();
            }
        }
        return BoardFields.defaultLabel(field);
    }

    /** 某榜全部字段的有效显示名（按榜型字段顺序）。 */
    public Map<String, String> labels(String code, RankType type) {
        var out = new LinkedHashMap<String, String>();
        for (String field : BoardFields.fieldsOf(type)) {
            out.put(field, label(code, field));
        }
        return out;
    }

    /**
     * 某榜全部字段的有效配置（label/列序/显隐/渲染），按列序升序。
     * <b>名次列 rank 强制第一、始终显示</b>；其余字段默认隐藏（必须显式打开），默认渲染 TEXT。
     */
    public List<BoardFieldConfig> fieldConfigs(String code, RankType type) {
        var ovs = fieldOverrides.get(groupKey(code));
        var fields = BoardFields.fieldsOf(type);
        var out = new ArrayList<BoardFieldConfig>(fields.size());
        int idx = 0;
        for (String f : fields) {
            FieldOverride ov = ovs == null ? null : ovs.get(f);
            boolean isRank = "rank".equals(f);
            String lbl = (ov != null && ov.label() != null) ? ov.label() : BoardFields.defaultLabel(f);
            int order = isRank ? -1 : (ov != null && ov.order() != null ? ov.order() : idx);
            boolean visible = isRank || (ov != null && ov.visible() != null && ov.visible());
            RenderType render = parseRender(ov != null ? ov.render() : null);
            out.add(new BoardFieldConfig(f, lbl, order, visible, render));
            idx++;
        }
        out.sort(Comparator.comparingInt(BoardFieldConfig::order));
        return out;
    }

    /** 部分更新某字段配置（非 null 项覆盖）。按榜组生效（同逻辑榜全区共用）。 */
    public void setFieldConfig(
            String code, String field, String label, Integer order, Boolean visible, RenderType render) {
        setFieldConfigByGroup(groupKey(code), field, label, order, visible, render);
    }

    private void setFieldConfigByGroup(
            String group, String field, String label, Integer order, Boolean visible, RenderType render) {
        String r = render == null ? null : render.name();
        store.upsertFieldConfig(group, field, label, order, visible, r);
        var m = fieldOverrides.computeIfAbsent(group, k -> new ConcurrentHashMap<>());
        FieldOverride cur = m.get(field);
        m.put(field, new FieldOverride(
                label != null ? label : (cur != null ? cur.label() : null),
                order != null ? order : (cur != null ? cur.order() : null),
                visible != null ? visible : (cur != null ? cur.visible() : null),
                r != null ? r : (cur != null ? cur.render() : null)));
        log.info("后台字段配置 [{}].{} label={} order={} visible={} render={}", group, field, label, order, visible, render);
    }

    public void setLabel(String code, String field, String label) {
        setFieldConfig(code, field, label, null, null, null);
    }

    private static RenderType parseRender(String s) {
        if (s == null) {
            return RenderType.TEXT;
        }
        try {
            return RenderType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return RenderType.TEXT;
        }
    }
}
