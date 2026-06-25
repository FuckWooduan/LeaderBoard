package com.rankharvester.web;

import com.rankharvester.rank.admin.BoardStateService;
import com.rankharvester.rank.admin.ManualRefreshService;
import com.rankharvester.rank.admin.RenderType;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.store.LadderRankStore;
import com.rankharvester.rank.store.LeaderboardCatalog;
import com.rankharvester.rank.store.ReducedRankStore;
import com.rankharvester.rank.store.TeamRankStore;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 排行榜后台 API（管理员用；鉴权将随 Passkey 一步加上，当前未鉴权）。
 *
 * <ul>
 *   <li>{@code GET  /api/boards}                       列出全部榜（含 enabled / public）</li>
 *   <li>{@code POST /api/boards/enable?code=&enabled=} 是否抓取（动态榜默认关）</li>
 *   <li>{@code POST /api/boards/public?code=&public=}  是否对前端开放（默认不开放）</li>
 *   <li>{@code POST /api/boards/refresh?code=}         最高优先级手动刷新</li>
 *   <li>{@code GET  /api/boards/fields?code=}          该榜字段 + 当前显示名</li>
 *   <li>{@code POST /api/boards/label?code=&field=&label=} 自定义字段显示名</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/boards")
public class BoardAdminController {

    private final LeaderboardCatalog catalog;
    private final BoardStateService boardState;
    private final ManualRefreshService refreshService;
    private final ReducedRankStore reducedStore;
    private final LadderRankStore ladderStore;
    private final TeamRankStore teamStore;
    private final java.sql.Connection conn;

    public BoardAdminController(
            LeaderboardCatalog catalog,
            BoardStateService boardState,
            ManualRefreshService refreshService,
            ReducedRankStore reducedStore,
            LadderRankStore ladderStore,
            TeamRankStore teamStore,
            java.sql.Connection duckDbConnection) {
        this.catalog = catalog;
        this.boardState = boardState;
        this.refreshService = refreshService;
        this.reducedStore = reducedStore;
        this.ladderStore = ladderStore;
        this.teamStore = teamStore;
        this.conn = duckDbConnection;
    }

    /** 取某榜组的首行（rank=1）全部列值，给后台做「示例值/渲染预览」。 */
    @GetMapping("/sample")
    public Map<String, Object> sample(@RequestParam String code) {
        LeaderboardDef def = require(code);
        String table = code.startsWith("LADDER") ? "ladder_rank" : code.startsWith("TEAM") ? "team_rank" : "reduced_rank";
        int i = code.lastIndexOf(':');
        String groupPrefix = (i < 0 ? code : code.substring(0, i)) + ":%"; // 同榜组任一区服的样本
        String orderBy = orderByOf(code, def.type()); // 示例=按排名依据(多条件)排序的首行（字段/方向已白名单校验）
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM " + table + " WHERE leaderboard_code LIKE ? ORDER BY "
                            + orderBy + " LIMIT 1")) {
                ps.setString(1, groupPrefix);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var meta = rs.getMetaData();
                        var row = new java.util.LinkedHashMap<String, Object>();
                        for (int c = 1; c <= meta.getColumnCount(); c++) {
                            row.put(meta.getColumnName(c), rs.getObject(c));
                        }
                        return row;
                    }
                }
            } catch (java.sql.SQLException e) {
                return Map.of("error", e.getMessage());
            }
        }
        return Map.of();
    }

    /** 数某查询返回的实际行数（在 conn 锁内）。 */
    private int countRows(String sql, String code) {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            int n = 0;
            try (var rs = ps.executeQuery()) {
                while (rs.next()) { n++; }
            }
            return n;
        } catch (java.sql.SQLException e) {
            return -1;
        }
    }

    /** 诊断：某 code 在落地表的真实行数/distinct/快照分布。 */
    @GetMapping("/debug")
    public Map<String, Object> debug(@RequestParam String code) {
        String table = code.startsWith("LADDER") ? "ladder_rank" : code.startsWith("TEAM") ? "team_rank" : "reduced_rank";
        var out = new java.util.LinkedHashMap<String, Object>();
        synchronized (conn) {
            try {
                try (var ps = conn.prepareStatement(
                        "SELECT COUNT(*), COUNT(DISTINCT rank) FROM " + table + " WHERE leaderboard_code=?")) {
                    ps.setString(1, code);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) { out.put("count", rs.getLong(1)); out.put("distinctRank", rs.getLong(2)); }
                    }
                }
                var snaps = new java.util.ArrayList<Map<String, Object>>();
                try (var ps = conn.prepareStatement(
                        "SELECT snapshot_id, COUNT(*) FROM " + table + " WHERE leaderboard_code=? GROUP BY 1")) {
                    ps.setString(1, code);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) { snaps.add(Map.of("snap", rs.getLong(1), "n", rs.getLong(2))); }
                    }
                }
                out.put("snapshots", snaps);
                // 强制全表扫描计数（OFFSET 0 阻止 COUNT 元数据优化）
                try (var ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM (SELECT rank FROM " + table + " WHERE leaderboard_code=? OFFSET 0) t")) {
                    ps.setString(1, code);
                    try (var rs = ps.executeQuery()) { if (rs.next()) out.put("scanCount", rs.getLong(1)); }
                }
                // 跑 CHECKPOINT 回收 tombstone，再次扫描计数
                try (var st = conn.createStatement()) { st.execute("CHECKPOINT"); }
                try (var ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM (SELECT rank FROM " + table + " WHERE leaderboard_code=? OFFSET 0) t")) {
                    ps.setString(1, code);
                    try (var rs = ps.executeQuery()) { if (rs.next()) out.put("scanCountAfterCheckpoint", rs.getLong(1)); }
                }
                try (var st = conn.createStatement(); var rs = st.executeQuery("PRAGMA version")) {
                    if (rs.next()) out.put("duckdbVersion", rs.getString(1));
                }
                // 精确复现 page 的查询，隔离 18 行来源
                var def = catalog.byCode(code).orElse(null);
                if (def != null) {
                    var vis = boardState.fieldConfigs(code, def.type()).stream()
                            .filter(com.rankharvester.rank.admin.BoardFieldConfig::visible)
                            .map(com.rankharvester.rank.admin.BoardFieldConfig::field).toList();
                    out.put("visibleFields", vis);
                    String cols = String.join(",", vis);
                    out.put("rows_orderby_limit", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? ORDER BY rank LIMIT 100 OFFSET 0", code));
                    out.put("rows_no_orderby", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=?", code));
                    out.put("rows_star", countRows(
                            "SELECT * FROM " + table + " WHERE leaderboard_code=?", code));
                    out.put("rows_orderby_quoted", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? ORDER BY \"rank\" LIMIT 100 OFFSET 0", code));
                    out.put("rows_orderby_noLimit", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? ORDER BY rank", code));
                    out.put("rows_subquery", countRows(
                            "SELECT * FROM (SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? ORDER BY rank) t LIMIT 100 OFFSET 0", code));
                    out.put("rows_limit_noorder", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? LIMIT 100 OFFSET 0", code));
                    // 修法②：row_number 窗口分页（避开 ORDER BY+LIMIT Top-N）
                    out.put("rows_window", countRows(
                            "SELECT " + cols + " FROM (SELECT " + cols + ", row_number() OVER (ORDER BY rank) rn FROM "
                                    + table + " WHERE leaderboard_code=?) t WHERE rn > 0 AND rn <= 100", code));
                    // 修法①：禁用 top_n 优化器后再 ORDER BY+LIMIT
                    try (var st = conn.createStatement()) { st.execute("PRAGMA disabled_optimizers='top_n'"); }
                    out.put("rows_orderby_limit_noTopN", countRows(
                            "SELECT " + cols + " FROM " + table + " WHERE leaderboard_code=? ORDER BY rank LIMIT 100 OFFSET 0", code));
                    try (var st = conn.createStatement()) { st.execute("PRAGMA disabled_optimizers=''"); }
                }
            } catch (java.sql.SQLException e) {
                out.put("error", e.getMessage());
            }
        }
        return out;
    }

    public record BoardView(
            String code, String type, String kind, String server, int partitionId,
            boolean enabled, boolean publicVisible) {}

    public record CountView(
            String code, String type, String server,
            boolean enabled, boolean publicVisible, long rows) {}

    public record FieldView(String field, String label) {}

    /** 字段完整配置视图（含列序/显隐/渲染）。 */
    public record FieldConfigView(String field, String label, int order, boolean visible, String render) {}

    /** 排名依据的一条：字段 + 方向。 */
    public record SortView(String field, String dir) {}

    public record FieldsView(String code, String type, List<FieldConfigView> fields,
            List<SortView> sorts) {}

    @GetMapping
    public List<BoardView> list() {
        return catalog.all().stream().map(this::toView).toList();
    }

    /** 全部榜 + 各自落库行数（DB 现状汇总）。 */
    @GetMapping("/counts")
    public List<CountView> counts() {
        return catalog.all().stream()
                .map(d -> new CountView(
                        d.code(), d.type().name(), d.server(),
                        boardState.isEnabled(d), boardState.isPublic(d.code()), rowsOf(d)))
                .toList();
    }

    private long rowsOf(LeaderboardDef d) {
        return switch (d.type()) {
            case REDUCED, PARTITION -> reducedStore.count(d.code());
            case LADDER -> ladderStore.count(d.code());
            case TEAM -> teamStore.count(d.code());
        };
    }

    @PostMapping("/enable")
    public BoardView enable(@RequestParam String code, @RequestParam(defaultValue = "true") boolean enabled) {
        LeaderboardDef def = require(code);
        boardState.setEnabled(code, enabled, System.currentTimeMillis());
        return toView(def);
    }

    @PostMapping("/public")
    public BoardView setPublic(@RequestParam String code, @RequestParam("public") boolean publicVisible) {
        LeaderboardDef def = require(code);
        boardState.setPublic(code, publicVisible, System.currentTimeMillis());
        return toView(def);
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh(@RequestParam String code) {
        try {
            String taskId = refreshService.trigger(code, System.currentTimeMillis());
            return Map.of("status", "queued", "code", code, "taskId", taskId, "lane", "MANUAL");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/fields")
    public FieldsView fields(@RequestParam String code) {
        LeaderboardDef def = require(code);
        var fields = boardState.fieldConfigs(code, def.type()).stream()
                .map(c -> new FieldConfigView(c.field(), c.label(), c.order(), c.visible(), c.render().name()))
                .toList();
        return new FieldsView(code, def.type().name(), fields, sortsView(code, def.type()));
    }

    private List<SortView> sortsView(String code, com.rankharvester.rank.model.RankType type) {
        return boardState.sortFor(code, type).stream().map(s -> new SortView(s.field(), s.dir())).toList();
    }

    /** 当前榜的有效排序为 SQL ORDER BY 串（多条件，字段已白名单校验）。 */
    private String orderByOf(String code, com.rankharvester.rank.model.RankType type) {
        var sb = new StringBuilder();
        for (var s : boardState.sortFor(code, type)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.field()).append(' ').append(s.dir());
        }
        return sb.toString();
    }

    /**
     * 设置排名依据（多条件）：{@code spec} 形如 {@code score_rebirth:DESC,score_number:DESC}，
     * 按顺序为优先级，名次=排序后行号。立即生效。
     */
    @PostMapping("/sort")
    public List<SortView> sort(@RequestParam String code, @RequestParam String spec) {
        LeaderboardDef def = require(code);
        var allowed = com.rankharvester.rank.admin.BoardFields.fieldsOf(def.type());
        var parsed = com.rankharvester.rank.store.BoardStateStore.parseSpec(spec, "DESC");
        var sorts = new java.util.ArrayList<com.rankharvester.rank.store.BoardStateStore.SortConfig>();
        for (var s : parsed) {
            if (!allowed.contains(s.field()) || "rank".equals(s.field())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段不属于该榜型或不可排序: " + s.field());
            }
            sorts.add(s);
        }
        if (sorts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序条件为空");
        }
        boardState.setSort(code, sorts);
        return sortsView(code, def.type());
    }

    @PostMapping("/label")
    public FieldView label(@RequestParam String code, @RequestParam String field, @RequestParam String label) {
        require(code);
        boardState.setLabel(code, field, label);
        return new FieldView(field, label);
    }

    /** 设置字段配置（任一参数可空，空=不改该项）。{@code render} ∈ TEXT/NUMBER/REALM/VIP。 */
    @PostMapping("/field")
    public FieldConfigView field(
            @RequestParam String code,
            @RequestParam String field,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) Integer order,
            @RequestParam(required = false) Boolean visible,
            @RequestParam(required = false) String render) {
        LeaderboardDef def = require(code);
        RenderType rt = null;
        if (render != null && !render.isBlank()) {
            try {
                rt = RenderType.valueOf(render.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知渲染类型: " + render);
            }
        }
        boardState.setFieldConfig(code, field, label, order, visible, rt);
        return boardState.fieldConfigs(code, def.type()).stream()
                .filter(c -> c.field().equals(field))
                .map(c -> new FieldConfigView(c.field(), c.label(), c.order(), c.visible(), c.render().name()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知字段: " + field));
    }

    private LeaderboardDef require(String code) {
        return catalog.byCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知排行榜: " + code));
    }

    private BoardView toView(LeaderboardDef d) {
        return new BoardView(
                d.code(), d.type().name(), d.kind().name(), d.server(), d.partitionId(),
                boardState.isEnabled(d), boardState.isPublic(d.code()));
    }
}
