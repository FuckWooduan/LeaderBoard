package com.rankharvester.rank.admin;

import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.store.LeaderboardCatalog;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 前端公开读：只暴露「已开放(public)」的榜；按榜分页读最新快照（每页 100），字段带显示名。
 *
 * <p>每个榜 = 具体 (榜型, rankType, 大区)，分页本身即按该榜的 server 过滤；
 * 「不选区服=全部」的跨服合并放在前端聚合层，本服务按 code 精确读。
 */
@Service
public class PublicRankService {

    public static final int PAGE_SIZE = 100;

    private final Connection conn;
    private final LeaderboardCatalog catalog;
    private final BoardStateService boardState;

    public PublicRankService(Connection duckDbConnection, LeaderboardCatalog catalog, BoardStateService boardState) {
        this.conn = duckDbConnection;
        this.catalog = catalog;
        this.boardState = boardState;
    }

    public record BoardBrief(String code, String type, String kind, String server) {}

    /** 一个可展示字段：列名 + 显示名 + 渲染类型（TEXT/NUMBER/REALM/VIP）。 */
    public record FieldView(String key, String label, String render) {}

    /** 区服选项：id=对外 districtId、name=区服名。 */
    public record ServerOption(String id, String name) {}

    public record RankPage(
            String code, String type, int page, int size, long total,
            List<FieldView> fields, List<Map<String, Object>> rows, long lastFetch) {}

    /**
     * 对前端可见的榜（榜选择器用）：后台<b>显式开放/关闭优先</b>（显式关闭即使有数据也不出现），
     * 无显式设置时「只要有数据就显示」（边抓边自动出现）。
     */
    public List<BoardBrief> publicBoards() {
        var withData = codesWithData();
        return catalog.all().stream()
                .filter(d -> shouldShow(d.code(), withData))
                .map(d -> new BoardBrief(d.code(), d.type().name(), d.kind().name(), d.server()))
                .toList();
    }

    /** 该榜是否对前端展示：后台显式开放/关闭优先（显式关闭即使有数据也隐藏）；无设置则「有数据即显示」。 */
    private boolean shouldShow(String code, Set<String> withData) {
        if (boardState.hasPublicOverride(code)) {
            return boardState.isPublic(code);
        }
        return withData.contains(code);
    }

    /** 当前在落地表里已有数据（行数&gt;0）的榜 code 集合。一表一次 DISTINCT，避免逐榜 COUNT。 */
    private Set<String> codesWithData() {
        var set = new HashSet<String>();
        synchronized (conn) {
            for (String t : List.of("reduced_rank", "ladder_rank", "team_rank")) {
                try (var st = conn.createStatement();
                        var rs = st.executeQuery("SELECT DISTINCT leaderboard_code FROM " + t)) {
                    while (rs.next()) {
                        set.add(rs.getString(1));
                    }
                } catch (SQLException ignore) {
                    // 表暂不可读（极少），跳过，不影响其它表
                }
            }
        }
        return set;
    }

    /** 该榜是否对前端可见：后台显式开放/关闭优先（显式关闭即使有数据也隐藏）；无设置则「有数据即可见」。 */
    private boolean visible(String code) {
        if (boardState.hasPublicOverride(code)) {
            return boardState.isPublic(code);
        }
        synchronized (conn) {
            String table = code.startsWith("LADDER") ? "ladder_rank"
                    : code.startsWith("TEAM") ? "team_rank" : "reduced_rank";
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE leaderboard_code = ? LIMIT 1")) {
                ps.setString(1, code);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public RankPage page(String code, int page) {
        return page(code, page, null, null);
    }

    public RankPage page(String code, int page, String q) {
        return page(code, page, q, null);
    }

    /**
     * 分页读某榜（必须有数据/已开放）。page 从 1 起，每页 100。
     * <p>{@code q} 非空时按角色名/战队<b>模糊过滤</b>；{@code vip} 非空时按 VIP 等级(score_all_number)过滤（仅 REDUCED 榜）。
     * 两者都<b>名次不变</b>——名次=该榜完整排序中的真实排名（过滤前用 row_number 算好）。
     */
    public RankPage page(String code, int page, String q, Integer vip) {
        LeaderboardDef def = catalog.byCode(code)
                .orElseThrow(() -> new IllegalArgumentException("未知排行榜: " + code));
        if (!visible(code)) {
            throw new IllegalStateException("排行榜暂无数据: " + code);
        }
        String table = tableOf(def.type());
        if (table == null) {
            throw new IllegalStateException("榜型暂不支持读取: " + def.type());
        }
        // 只取「已打开显示」的列，按后台配置的列序（rank 强制第一）；列名来自静态白名单，非用户输入。
        var visible = boardState.fieldConfigs(code, def.type()).stream()
                .filter(BoardFieldConfig::visible)
                .toList();
        var fieldViews = visible.stream()
                .map(c -> new FieldView(c.field(), c.label(), c.render().name()))
                .toList();
        // 排名依据：按后台配置的多条件（有序）排序，名次=排序后行号（不再用抓包顺序的 rank 列）。
        // 各条字段/方向由 BoardStateService.sortFor 做白名单校验，可安全内联进 SQL。
        var sorts = boardState.sortFor(code, def.type());
        var ob = new StringBuilder();
        for (var s : sorts) {
            if (ob.length() > 0) ob.append(", ");
            ob.append(s.field()).append(' ').append(s.dir());
        }
        String orderBy = ob.toString();
        List<String> dataFields = visible.stream()
                .map(BoardFieldConfig::field)
                .filter(f -> !"rank".equals(f))
                .toList();
        int p = Math.max(1, page);
        int offset = (p - 1) * PAGE_SIZE;

        // base：reduced 先按 score_gc_id 取最新 snapshot 去重；ladder/team 直接取本榜。
        boolean dedup = "reduced_rank".equals(table);
        String base = dedup
                ? "(SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY score_gc_id ORDER BY snapshot_id DESC)"
                        + " AS _rn FROM " + table + " WHERE leaderboard_code = ?) d WHERE _rn = 1)"
                : "(SELECT * FROM " + table + " WHERE leaderboard_code = ?)";
        // 过滤（模糊角色名/战队 + VIP 等级）；名次在过滤前用 row_number 全榜算好 → 过滤后名次不变。
        String[] sc = searchCols(def.type());
        String qLike = (q == null || q.isBlank() || sc.length != 2) ? null : "%" + q.trim() + "%";
        Integer vipFilter = (def.type() == RankType.REDUCED && vip != null) ? vip : null;
        var conds = new ArrayList<String>();
        if (qLike != null) conds.add("(" + sc[0] + " ILIKE ? OR " + sc[1] + " ILIKE ?)");
        if (vipFilter != null) conds.add("score_all_number = ?");
        String filter = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
        String cols = dataFields.isEmpty() ? sorts.get(0).field() : String.join(",", dataFields);

        synchronized (conn) {
            long total;
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + base + " b" + filter)) {
                int i = 1;
                ps.setString(i++, code);
                if (qLike != null) { ps.setString(i++, qLike); ps.setString(i++, qLike); }
                if (vipFilter != null) ps.setInt(i++, vipFilter);
                try (var rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计失败: " + code, e);
            }

            var rows = new ArrayList<Map<String, Object>>();
            String sql = "SELECT " + cols + ", _rank FROM (SELECT *, row_number() OVER (ORDER BY " + orderBy
                    + ") AS _rank FROM " + base + " b) r" + filter + " ORDER BY _rank LIMIT ? OFFSET ?";
            try (var ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, code);
                if (qLike != null) { ps.setString(i++, qLike); ps.setString(i++, qLike); }
                if (vipFilter != null) ps.setInt(i++, vipFilter);
                ps.setInt(i++, PAGE_SIZE);
                ps.setInt(i, offset);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("rank", rs.getInt("_rank")); // 名次 = 全榜真实排名（过滤前算好）
                        for (String f : dataFields) {
                            row.put(f, rs.getObject(f));
                        }
                        rows.add(row);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("分页读取失败: " + code, e);
            }

            long lastFetch = maxFetch(table, "leaderboard_code = ?", code);
            return new RankPage(code, def.type().name(), p, PAGE_SIZE, total, fieldViews, rows, lastFetch);
        }
    }

    /** 某榜最近抓取时间（epoch ms）= 该榜数据行 {@code fetched_at} 的最大值；无数据返回 0。 */
    private long maxFetch(String table, String whereSql, String codeBind) {
        // 「上次上分时间」：reduced 榜用游戏侧 dateline(上分时间)，team/ladder 无该列则回落 fetched_at(抓取时间)。
        boolean useDateline = "reduced_rank".equals(table);
        String timeCol = useDateline ? "dateline" : "fetched_at";
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT MAX(" + timeCol + ") FROM " + table + " WHERE " + whereSql)) {
                if (codeBind != null) {
                    ps.setString(1, codeBind);
                }
                try (var rs = ps.executeQuery()) {
                    long raw = rs.next() ? rs.getLong(1) : 0L;
                    if (raw <= 0) {
                        return 0L;
                    }
                    // dateline 为游戏侧 Unix 秒(10位)，转毫秒供前端；已是毫秒(≥1e12)或 fetched_at 则原样。
                    return (useDateline && raw < 1_000_000_000_000L) ? raw * 1000L : raw;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 某榜有效排序的 SQL ORDER BY 串（多条件，字段已白名单校验）。 */
    private String orderByFor(String code, RankType type) {
        var sb = new StringBuilder();
        for (var s : boardState.sortFor(code, type)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.field()).append(' ').append(s.dir());
        }
        return sb.toString();
    }

    /** 玩家在某 REDUCED 榜的名次 + 分值。 */
    public record PlayerOnBoard(int rank, long score) {}

    /**
     * 在某 REDUCED 榜（战力 382 / 经验 331 等）按<b>精确角色名</b>查该玩家的真实名次+分值；不在榜返回 null。
     * 名次=该榜完整排序中的真实排名（同名取名次最高者）。
     */
    public PlayerOnBoard playerRankIn(String code, String charName) {
        var def = catalog.byCode(code).orElse(null);
        if (def == null || def.type() != RankType.REDUCED || charName == null || charName.isBlank()) {
            return null;
        }
        String orderBy = orderByFor(code, def.type());
        String sql = "SELECT _rank, score_number FROM (SELECT *, row_number() OVER (ORDER BY " + orderBy
                + ") AS _rank FROM (SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY score_gc_id"
                + " ORDER BY snapshot_id DESC) AS _rn FROM reduced_rank WHERE leaderboard_code = ?) d WHERE _rn = 1) b) r"
                + " WHERE info_char_name = ? ORDER BY _rank LIMIT 1";
        synchronized (conn) {
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, code);
                ps.setString(2, charName);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerOnBoard(rs.getInt(1), rs.getLong(2));
                    }
                }
            } catch (SQLException ignore) {
                // 榜暂不可读 → 返回 null（详情可缺省）
            }
        }
        return null;
    }

    /** 各榜型可模糊搜索的列：[角色名列, 战队列]。 */
    private static String[] searchCols(RankType type) {
        return switch (type) {
            case REDUCED -> new String[]{"info_char_name", "info_team_name"};
            case LADDER -> new String[]{"char_name", "team_name"};
            case TEAM -> new String[]{"team_name", "leader_name"};
            default -> new String[]{};
        };
    }

    /**
     * 标签可选的区服列表，<b>对外用 districtId</b>（玩家在游戏里看到的服号；内部 code 用登录 id）。
     */
    public List<ServerOption> serversForTab(String tab) {
        String prefix = perServerPrefix(tab);
        var withData = codesWithData();
        // 榜按登录 id 分（code 末段即 loginId）；直接用 loginId 作选项值，名称用区服名。
        // 后台显式开放/关闭优先（显式关闭即使有数据也不列），无设置则「有数据即列」。
        return catalog.all().stream()
                .map(LeaderboardDef::code)
                .filter(c -> shouldShow(c, withData))
                .filter(c -> c.startsWith(prefix))
                .map(PublicRankService::lastSegment) // 登录 id
                .distinct()
                .sorted(java.util.Comparator.comparingInt(PublicRankService::parseIntSafe))
                .map(id -> new ServerOption(id, com.rankharvester.net.GameServer.nameOfId(id)))
                .toList();
    }

    /**
     * 标签 + 区服 → 榜 code。{@code server} 为 <b>districtId</b>（对外）；内部映射回登录 id 拼 code。
     * 区服空=全部/跨服 canonical。
     */
    public String resolveCode(String tab, String server) {
        boolean all = server == null || server.isBlank();
        // server 即 loginId（榜按登录 id 分），直接拼 code。
        return switch (tab) {
            case "power" -> all ? canonical("REDUCED:333:") : "REDUCED:382:" + server;
            case "exp" -> all ? canonical("REDUCED:332:") : "REDUCED:331:" + server;
            case "ladder" -> all ? canonical("LADDER:23:") : "LADDER:23:" + server;
            default -> throw new IllegalArgumentException("未知标签: " + tab);
        };
    }

    /** 按标签分页读（q 模糊过滤角色名/战队，vip 过滤 VIP 等级；名次不变）。 */
    public RankPage pageByTab(String tab, String server, int page, String q, Integer vip) {
        return page(resolveCode(tab, server), page, q, vip);
    }

    /**
     * 战队榜专用：{@code server} 空=<b>全服</b>（聚合所有区 TEAM:* 一起比），否则单区。
     * {@code sortMode="adventure"} 按<b>冒险</b>(rpg_team_total_bonus)排，否则按<b>普通</b>(team_exp)排。
     * 名次=排序后真实排名；{@code q} 模糊战队名/队长，名次不变。排序为战队榜特例，不走后台排名依据。
     */
    public RankPage teamPage(String server, int page, String q, String sortMode) {
        boolean allServers = server == null || server.isBlank();
        String fieldCode = allServers ? "TEAM:all" : "TEAM:" + server;
        var visible = boardState.fieldConfigs(fieldCode, RankType.TEAM).stream()
                .filter(BoardFieldConfig::visible).toList();
        var fieldViews = new ArrayList<>(visible.stream()
                .map(c -> new FieldView(c.field(), c.label(), c.render().name())).toList());
        // 全服比拼时，最后加一列「区服」显示该战队所在区（带运营商色）。
        if (allServers) {
            fieldViews.add(new FieldView("server_district", "区服", "DISTRICT"));
        }
        List<String> dataFields = visible.stream()
                .map(BoardFieldConfig::field).filter(f -> !"rank".equals(f)).toList();
        // 冒险贡献 unsigned32 还原（存量负值兼容；新数据已在解析层修正，CASE 对正值无影响）。
        String bonus = "(CASE WHEN rpg_team_total_bonus < 0 THEN rpg_team_total_bonus + 4294967296 ELSE rpg_team_total_bonus END)";
        String orderBy = "adventure".equals(sortMode)
                ? bonus + " DESC, team_exp DESC"
                : "team_exp DESC, " + bonus + " DESC";
        String codeWhere = allServers ? "leaderboard_code LIKE 'TEAM:%'" : "leaderboard_code = ?";
        String base = "(SELECT * FROM team_rank WHERE " + codeWhere + ")";
        String qLike = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        String filter = qLike != null ? " WHERE (team_name ILIKE ? OR leader_name ILIKE ?)" : "";
        String cols = dataFields.isEmpty() ? "team_name" : String.join(",",
                dataFields.stream().map(f -> "rpg_team_total_bonus".equals(f) ? bonus + " AS rpg_team_total_bonus" : f).toList());
        if (allServers) {
            cols += ", server_id"; // 用于解析每个战队所在区
        }
        int p = Math.max(1, page), offset = (p - 1) * PAGE_SIZE;
        synchronized (conn) {
            long total;
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + base + " b" + filter)) {
                int i = 1;
                if (!allServers) ps.setString(i++, fieldCode);
                if (qLike != null) { ps.setString(i++, qLike); ps.setString(i++, qLike); }
                try (var rs = ps.executeQuery()) { total = rs.next() ? rs.getLong(1) : 0L; }
            } catch (SQLException e) {
                throw new IllegalStateException("统计失败: team", e);
            }
            var rows = new ArrayList<Map<String, Object>>();
            String sql = "SELECT " + cols + ", _rank FROM (SELECT *, row_number() OVER (ORDER BY " + orderBy
                    + ") AS _rank FROM " + base + " b) r" + filter + " ORDER BY _rank LIMIT ? OFFSET ?";
            try (var ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (!allServers) ps.setString(i++, fieldCode);
                if (qLike != null) { ps.setString(i++, qLike); ps.setString(i++, qLike); }
                ps.setInt(i++, PAGE_SIZE);
                ps.setInt(i, offset);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("rank", rs.getInt("_rank"));
                        for (String f : dataFields) row.put(f, rs.getObject(f));
                        if (allServers) {
                            String dist = com.rankharvester.net.GameServer.districtOfLoginId(
                                    String.valueOf(rs.getInt("server_id")));
                            row.put("server_district", dist != null ? Integer.valueOf(dist) : rs.getInt("server_id"));
                        }
                        rows.add(row);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("分页读取失败: team", e);
            }
            long lastFetch = maxFetch("team_rank",
                    allServers ? "leaderboard_code LIKE 'TEAM:%'" : "leaderboard_code = ?",
                    allServers ? null : fieldCode);
            return new RankPage(fieldCode, "TEAM", p, PAGE_SIZE, total, fieldViews, rows, lastFetch);
        }
    }

    /** 找该前缀下第一个有数据/已开放的榜（用于全部/跨服）。 */
    private String canonical(String prefix) {
        var withData = codesWithData();
        return catalog.all().stream()
                .map(LeaderboardDef::code)
                .filter(c -> withData.contains(c) || boardState.isPublic(c))
                .filter(c -> c.startsWith(prefix))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("暂无数据的榜: " + prefix));
    }

    private static String perServerPrefix(String tab) {
        return switch (tab) {
            case "power" -> "REDUCED:382:";
            case "exp" -> "REDUCED:331:";
            case "ladder" -> "LADDER:23:";
            case "team" -> "TEAM:";
            default -> throw new IllegalArgumentException("未知标签: " + tab);
        };
    }

    private static String lastSegment(String code) {
        int i = code.lastIndexOf(':');
        return i < 0 ? code : code.substring(i + 1);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String tableOf(RankType type) {
        return switch (type) {
            case REDUCED -> "reduced_rank";
            case LADDER -> "ladder_rank";
            case TEAM -> "team_rank";
            default -> null;
        };
    }
}
