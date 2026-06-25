package com.rankharvester.rank.slice;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 分区排行榜「分区获取」落地（DuckDB）。
 *
 * <p>{@code getRankSliceGroupMap} 每个 (账号×服务器) 登录返回 {@code {rankKey: 分片数}}。两张表：
 * <ul>
 *   <li>{@code rank_slice_group}：每个 (账号, 服务器, rankKey) → 分片数 slice_count（覆盖来源）。</li>
 *   <li>{@code rank_key_meta}：每个 rankKey → 命名 label + 静态/动态 dynamic + 首次发现时间。
 *       新出现的 rankKey 默认静态（不更新）；标记 dynamic 的每 30 分钟刷新。</li>
 * </ul>
 * 进度格子（前端）：选 rankKey → 200 格(分片 0-199)，每格颜色 = 覆盖该分片的来源数（slice_count &gt; 分片号）。
 */
@Repository
public class RankSliceStore {

    private static final Logger log = LoggerFactory.getLogger(RankSliceStore.class);
    /** 一个 rankKey 最多分片数（区 0-199，显示 1-200）。 */
    public static final int MAX_SLICES = 200;

    private final Connection conn;
    private final ObjectMapper mapper;
    /** rank_key_meta 的 JSON 镜像文件（宿主机挂载、绝对持久）。DuckDB 第二连接近期提交跨重启会丢，故另存一份权威快照。 */
    private final Path metaFile;
    /** 已知 rankKey 内存集合：热路径用它判新 key，避免每条记录都查一次 SQL（减少写锁占用）。 */
    private final java.util.Set<String> knownKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 注入分区获取专用的第二连接（按 bean 名注入），与主连接 MVCC 并发，互不抢锁。
    public RankSliceStore(
            @org.springframework.beans.factory.annotation.Qualifier("duckDbSliceConnection")
            Connection duckDbSliceConnection,
            ObjectMapper mapper,
            @Value("${rankharvester.duckdb.path:data/rank.duckdb}") String dbPath) {
        this.conn = duckDbSliceConnection;
        this.mapper = mapper;
        Path db = Path.of(dbPath).toAbsolutePath();
        this.metaFile = (db.getParent() == null ? Path.of(".") : db.getParent()).resolve("rank_key_meta.json");
    }

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                // partition_idx = 该 (账号×区) 在此 rankKey 所属的分区号(0-199)。
                st.execute("""
                        CREATE TABLE IF NOT EXISTS rank_slice_group(
                            account_id   VARCHAR,
                            server       VARCHAR,
                            rank_key     VARCHAR,
                            partition_idx INTEGER,
                            updated_at   BIGINT,
                            PRIMARY KEY (account_id, server, rank_key)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS rank_key_meta(
                            rank_key    VARCHAR PRIMARY KEY,
                            label       VARCHAR,
                            dynamic     BOOLEAN DEFAULT false,
                            visible     BOOLEAN DEFAULT true,
                            hidden      BOOLEAN DEFAULT false,
                            prev_season VARCHAR,
                            partition_count INTEGER DEFAULT 200,
                            season_end_at BIGINT DEFAULT 0,
                            brawl       BOOLEAN DEFAULT false,
                            categories  VARCHAR,
                            first_seen  BIGINT,
                            updated_at  BIGINT
                        )""");
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS visible BOOLEAN DEFAULT true");
                // 隐藏榜身份预测：hidden=标记为隐藏赛季榜；prev_season=上一赛季对应的 rankKey（层层递归匹配名字）
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT false");
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS prev_season VARCHAR");
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS partition_count INTEGER DEFAULT 200");
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS season_end_at BIGINT DEFAULT 0");
                // 乱斗榜：标记后对隐藏玩家额外用 AI 预测分数（据战力/VIP/境界 + 同分区可见分数锚点）
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS brawl BOOLEAN DEFAULT false");
                // 分类标签：多值 JSON 字符串，如 ["异能榜","乱斗榜"]；后台可自定义，API 支持模糊筛选。
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS categories VARCHAR");
                // 同区榜：手动指定该榜与哪个 rankKey 同分区（候选源/层层递归用）。设了则覆盖自动重叠判定。
                st.execute("ALTER TABLE rank_key_meta ADD COLUMN IF NOT EXISTS same_zone VARCHAR");
            } catch (SQLException e) {
                throw new IllegalStateException("建分区表失败", e);
            }
            // 旧版列名迁移（slice_count → partition_idx）；用独立 Statement，失败会关闭它但不影响其它语句。
            try (var alterSt = conn.createStatement()) {
                alterSt.execute("ALTER TABLE rank_slice_group RENAME slice_count TO partition_idx");
            } catch (SQLException ignore) {
                // 已是新列名或表本就新建
            }
            // 预载已知 rankKey 到内存
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT rank_key FROM rank_key_meta")) {
                while (rs.next()) {
                    knownKeys.add(rs.getString(1));
                }
            } catch (SQLException ignore) {
                // 空表/首次
            }
            // 从 JSON 镜像回灌 meta（权威），覆盖 DuckDB 里可能因第二连接重启丢失的近期改动。
            loadMetaFromFile();
        }
        log.info("分区表 rank_slice_group / rank_key_meta 就绪（已知 rankKey {} 个）", knownKeys.size());
    }

    /** 一条 (账号×服务器) 的采集结果。 */
    public record Source(String accountId, String server, Map<String, Integer> rankKeyToPartition) {}

    /**
     * <b>批量</b>落库多条 (账号×服务器) 采集结果（单事务、单次锁），大幅降低与读请求的连接争用。
     * 返回其中<b>新出现</b>的 rankKey。
     */
    public List<String> recordBatch(List<Source> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        var newKeys = new ArrayList<String>();
        for (Source s : batch) {
            for (String rk : s.rankKeyToPartition().keySet()) {
                if (knownKeys.add(rk)) {
                    newKeys.add(rk);
                }
            }
        }
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(
                        "INSERT INTO rank_slice_group(account_id,server,rank_key,partition_idx,updated_at)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT (account_id,server,rank_key)"
                        + " DO UPDATE SET partition_idx=excluded.partition_idx, updated_at=excluded.updated_at")) {
                    for (Source s : batch) {
                        for (var e : s.rankKeyToPartition().entrySet()) {
                            ps.setString(1, s.accountId());
                            ps.setString(2, s.server());
                            ps.setString(3, e.getKey());
                            ps.setInt(4, e.getValue() == null ? 0 : e.getValue());
                            ps.setLong(5, now);
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                if (!newKeys.isEmpty()) {
                    try (var metaIns = conn.prepareStatement(
                            "INSERT INTO rank_key_meta(rank_key,label,dynamic,first_seen,updated_at)"
                            + " VALUES(?,NULL,true,?,?) ON CONFLICT (rank_key) DO NOTHING")) { // 新榜默认动态=抓；结束后手动改静态停抓
                        for (String rk : newKeys) {
                            metaIns.setString(1, rk);
                            metaIns.setLong(2, now);
                            metaIns.setLong(3, now);
                            metaIns.addBatch();
                        }
                        metaIns.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                newKeys.forEach(knownKeys::remove);
                throw new IllegalStateException("批量写分区覆盖失败", ex);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        return newKeys;
    }

    /**
     * 一条 (账号×服务器) 的 getRankSliceGroupMap 结果落库：{@code rankKeyToPartition} 的值是
     * 该账号在对应 rankKey 所属的<b>分区号</b>(0-199)。返回其中<b>新出现</b>的 rankKey。
     */
    public List<String> recordSliceCounts(String accountId, String server, Map<String, Integer> rankKeyToPartition) {
        long now = System.currentTimeMillis();
        // 新 key 检测走内存集合（无 SQL）；knownKeys.add 返回 true 即新。
        var newKeys = new ArrayList<String>();
        for (String rk : rankKeyToPartition.keySet()) {
            if (knownKeys.add(rk)) {
                newKeys.add(rk);
            }
        }
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(
                        "INSERT INTO rank_slice_group(account_id,server,rank_key,partition_idx,updated_at)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT (account_id,server,rank_key)"
                        + " DO UPDATE SET partition_idx=excluded.partition_idx, updated_at=excluded.updated_at")) {
                    for (var e : rankKeyToPartition.entrySet()) {
                        ps.setString(1, accountId);
                        ps.setString(2, server);
                        ps.setString(3, e.getKey());
                        ps.setInt(4, e.getValue() == null ? 0 : e.getValue());
                        ps.setLong(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                if (!newKeys.isEmpty()) {
                    try (var metaIns = conn.prepareStatement(
                            "INSERT INTO rank_key_meta(rank_key,label,dynamic,first_seen,updated_at)"
                            + " VALUES(?,NULL,true,?,?) ON CONFLICT (rank_key) DO NOTHING")) { // 新榜默认动态=抓；结束后手动改静态停抓
                        for (String rk : newKeys) {
                            metaIns.setString(1, rk);
                            metaIns.setLong(2, now);
                            metaIns.setLong(3, now);
                            metaIns.addBatch();
                        }
                        metaIns.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                // 写失败：把误加入内存的新 key 撤回，以便下次重试仍能检测
                newKeys.forEach(knownKeys::remove);
                throw new IllegalStateException("写分区覆盖失败", ex);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        if (!newKeys.isEmpty()) {
            log.info("发现新 rankKey {} 个: {}", newKeys.size(), newKeys);
        }
        return newKeys;
    }

    /** rankKey 列表（含命名/静态动态/来源数/最大分片数）。 */
    public List<KeyInfo> listKeys() {
        var out = new ArrayList<KeyInfo>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT m.rank_key, m.label, m.dynamic, COALESCE(m.visible, true) AS visible,"
                            + " COALESCE(g.sources,0) AS sources, COALESCE(g.max_part,-1)+1 AS max_slices,"
                            + " COALESCE(pr.last_fetch,0) AS last_fetch,"
                            + " COALESCE(m.hidden, false) AS hidden, m.prev_season,"
                            + " COALESCE(m.brawl, false) AS brawl, m.same_zone, m.categories,"
                            + " COALESCE(m.partition_count, 200) AS partition_count, COALESCE(m.season_end_at, 0) AS season_end_at"
                            + " FROM rank_key_meta m"
                            + " LEFT JOIN (SELECT rank_key, COUNT(*) AS sources, MAX(partition_idx) AS max_part"
                            + "            FROM rank_slice_group GROUP BY rank_key) g ON g.rank_key = m.rank_key"
                            + " LEFT JOIN (SELECT rank_key, MAX(updated_at) AS last_fetch"
                            + "            FROM partition_rank GROUP BY rank_key) pr ON pr.rank_key = m.rank_key"
                            + " ORDER BY m.rank_key")) {
                while (rs.next()) {
                    out.add(new KeyInfo(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                            rs.getBoolean(4), rs.getInt(5), rs.getInt(6), rs.getLong(7),
                            rs.getBoolean(8), rs.getString(9), rs.getBoolean(10), rs.getString(11),
                            parseCategories(rs.getString(12), rs.getBoolean(10)),
                            clampPartitionCount(rs.getInt(13)), rs.getLong(14)));
                }
            } catch (SQLException e) {
                log.warn("listKeys 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 某 rankKey 的分区占位：长度 200 的数组，grid[i] = <b>恰好属于分区 i</b> 的 (账号×区) 数。红(0)=该分区没号→抓不了。 */
    public int[] coverageGrid(String rankKey) {
        int[] grid = new int[MAX_SLICES];
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT partition_idx, COUNT(*) FROM rank_slice_group WHERE rank_key=? GROUP BY partition_idx")) {
                ps.setString(1, rankKey);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int p = rs.getInt(1);
                        if (p >= 0 && p < MAX_SLICES) {
                            grid[p] = rs.getInt(2);
                        }
                    }
                }
            } catch (SQLException e) {
                log.warn("coverageGrid 失败: {}", e.getMessage());
            }
        }
        return grid;
    }

    /** 原始 rank_slice_group 行（按账号分组查看真实结构：同号同区下各 rankKey 的分区号）。 */
    public List<Map<String, Object>> sampleGroupRows(int accountLimit) {
        var out = new ArrayList<Map<String, Object>>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT account_id, server, rank_key, partition_idx FROM rank_slice_group"
                    + " WHERE account_id IN (SELECT DISTINCT account_id FROM rank_slice_group ORDER BY account_id LIMIT ?)"
                    + " ORDER BY account_id, server, rank_key")) {
                ps.setInt(1, accountLimit);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var m = new java.util.LinkedHashMap<String, Object>();
                        m.put("account_id", rs.getString(1));
                        m.put("server", rs.getString(2));
                        m.put("rank_key", rs.getString(3));
                        m.put("partition_idx", rs.getInt(4));
                        out.add(m);
                    }
                }
            } catch (SQLException e) {
                log.warn("sampleGroupRows 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 两个 rankKey 的「同账号同分区」一致率：same/total 越接近 1，越属于同一分区分组（候选集可互查）。 */
    public record GroupAgreement(String a, String b, long same, long total) {}

    /** 两两 rankKey 的分区一致率（基于 rank_slice_group：同一 (账号,区) 在两榜是否分到同一分区）。 */
    public List<GroupAgreement> groupAgreement() {
        var out = new ArrayList<GroupAgreement>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT x.rank_key AS a, y.rank_key AS b,"
                            + " SUM(CASE WHEN x.partition_idx = y.partition_idx THEN 1 ELSE 0 END) AS same,"
                            + " COUNT(*) AS total"
                            + " FROM rank_slice_group x JOIN rank_slice_group y"
                            + "   ON x.account_id = y.account_id AND x.server = y.server AND x.rank_key < y.rank_key"
                            + " GROUP BY x.rank_key, y.rank_key ORDER BY a, b")) {
                while (rs.next()) {
                    out.add(new GroupAgreement(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
                }
            } catch (SQLException e) {
                log.warn("groupAgreement 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * 取与 {@code rankKey} 同分区分组、且在 {@code partition} 分区有命名玩家的「候选集来源榜」里，该分区的全部命名玩家。
     * 用于隐藏玩家 AI 身份预测的候选集。返回的每行含 char_name/gc_id/score_number 等。
     */
    public List<String> sameGroupKeys(String rankKey, double threshold) {
        var keys = new ArrayList<String>();
        for (var g : groupAgreement()) {
            if (g.total() == 0) {
                continue;
            }
            double rate = (double) g.same() / g.total();
            if (rate >= threshold) {
                if (g.a().equals(rankKey)) {
                    keys.add(g.b());
                } else if (g.b().equals(rankKey)) {
                    keys.add(g.a());
                }
            }
        }
        return keys;
    }

    /** 一个 (账号×区) 来源 + 它在各 rankKey 所属的分区（登录一次即可抓这些 rankKey 的分区榜）。 */
    public record SourceTask(String accountId, String server, Map<String, Integer> rankKeyToPartition) {}

    /** 按 (账号×区) 分组取全部采集到的分区归属——用于「一次登录抓该号所有 rankKey 分区榜」。 */
    public List<SourceTask> allSourceTasks() {
        var map = new java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, Integer>>();
        var meta = new java.util.LinkedHashMap<String, String[]>();
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT account_id, server, rank_key, partition_idx FROM rank_slice_group"
                            + " ORDER BY account_id, server")) {
                while (rs.next()) {
                    String acc = rs.getString(1);
                    String srv = rs.getString(2);
                    String key = acc + "@" + srv;
                    map.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>()).put(rs.getString(3), rs.getInt(4));
                    meta.putIfAbsent(key, new String[] {acc, srv});
                }
            } catch (SQLException e) {
                log.warn("allSourceTasks 失败: {}", e.getMessage());
            }
        }
        var out = new ArrayList<SourceTask>(map.size());
        for (var e : map.entrySet()) {
            String[] m = meta.get(e.getKey());
            out.add(new SourceTask(m[0], m[1], e.getValue()));
        }
        return out;
    }

    /** 取属于某 (rankKey, 分区) 的账号们（抓该分区排行榜时从中选号登录）。 */
    public List<String[]> accountsInPartition(String rankKey, int partition) {
        var out = new ArrayList<String[]>();
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT account_id, server FROM rank_slice_group WHERE rank_key=? AND partition_idx=?")) {
                ps.setString(1, rankKey);
                ps.setInt(2, partition);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[] {rs.getString(1), rs.getString(2)});
                    }
                }
            } catch (SQLException e) {
                log.warn("accountsInPartition 失败: {}", e.getMessage());
            }
        }
        return out;
    }

    public void setLabel(String rankKey, String label) {
        execMeta("UPDATE rank_key_meta SET label=?, updated_at=? WHERE rank_key=?", label, rankKey);
    }

    public void setDynamic(String rankKey, boolean dynamic) {
        setFlag("dynamic", rankKey, dynamic);
    }

    public void setHidden(String rankKey, boolean hidden) {
        setFlag("hidden", rankKey, hidden);
    }

    public void setBrawl(String rankKey, boolean brawl) {
        setFlag("brawl", rankKey, brawl);
    }

    /** 设置分类标签（多值；自动去空、去重）。 */
    public void setCategories(String rankKey, List<String> categories) {
        var normalized = normalizeCategories(categories);
        String json;
        try {
            json = mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("序列化分类标签失败", e);
        }
        execMeta("UPDATE rank_key_meta SET categories=?, updated_at=? WHERE rank_key=?", json, rankKey);
    }

    /** 设置上一赛季对应的 rankKey（空串=清除）。隐藏玩家按 last_rank 去上赛季榜递归找名字。 */
    public void setPrevSeason(String rankKey, String prevSeason) {
        String v = (prevSeason == null || prevSeason.isBlank()) ? null : prevSeason.trim();
        execMeta("UPDATE rank_key_meta SET prev_season=?, updated_at=? WHERE rank_key=?", v, rankKey);
    }

    /** 设置该榜实际分区数量（1-200）。例如深空争霸只有 1 个分区，避免调度误等 200 区。 */
    public void setPartitionCount(String rankKey, int partitionCount) {
        execMeta("UPDATE rank_key_meta SET partition_count=?, updated_at=? WHERE rank_key=?",
                clampPartitionCount(partitionCount), rankKey);
    }

    /** 设置赛季结束时间（epoch ms；0=清除）。调度会在结束前预留时间触发季末抢数。 */
    public void setSeasonEndAt(String rankKey, long seasonEndAt) {
        execMeta("UPDATE rank_key_meta SET season_end_at=?, updated_at=? WHERE rank_key=?",
                Math.max(0L, seasonEndAt), rankKey);
    }

    /** 手动指定该榜的「同区榜」rankKey（空串=清除，回落自动重叠判定）。设了即覆盖 {@link #sameGroupKeys} 的自动结果。 */
    public void setSameZone(String rankKey, String sameZone) {
        String v = (sameZone == null || sameZone.isBlank()) ? null : sameZone.trim();
        execMeta("UPDATE rank_key_meta SET same_zone=?, updated_at=? WHERE rank_key=?", v, rankKey);
    }

    /** 该榜手动指定的同区榜 rankKey；未设返回 null（回落自动判定）。 */
    public String sameZoneOf(String rankKey) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("SELECT same_zone FROM rank_key_meta WHERE rank_key=?")) {
                ps.setString(1, rankKey);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String v = rs.getString(1);
                        return (v == null || v.isBlank()) ? null : v.trim();
                    }
                }
            } catch (SQLException e) {
                log.warn("sameZoneOf 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    public void setVisible(String rankKey, boolean visible) {
        setFlag("visible", rankKey, visible);
    }

    private void setFlag(String col, String rankKey, boolean val) {
        long now = System.currentTimeMillis();
        synchronized (conn) {
            // 与发现逻辑一致的显式事务：不能依赖隐式 autocommit（事务边界期间它可能为 false，
            // 导致 UPDATE 未真正提交、重启/下次事务即丢，造成「显示设置自己回弹」）。
            try {
                boolean acBefore = conn.getAutoCommit();
                conn.setAutoCommit(false);
                int updated;
                try (var ps = conn.prepareStatement(
                        "UPDATE rank_key_meta SET " + col + "=?, updated_at=? WHERE rank_key=?")) {
                    ps.setBoolean(1, val);
                    ps.setLong(2, now);
                    ps.setString(3, rankKey);
                    updated = ps.executeUpdate();
                }
                conn.commit();
                Boolean readback = null;
                try (var ps2 = conn.prepareStatement("SELECT " + col + " FROM rank_key_meta WHERE rank_key=?")) {
                    ps2.setString(1, rankKey);
                    try (var rs = ps2.executeQuery()) {
                        if (rs.next()) {
                            readback = rs.getBoolean(1);
                        }
                    }
                }
                log.info("[meta诊断] setFlag col={} key={} val={} updated={} readback={} acBefore={}",
                        col, rankKey, val, updated, readback, acBefore);
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("设置 " + col + " 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
            persistMetaToFile();
        }
    }

    /**
     * 把 rank_key_meta 全量快照到 JSON 文件（宿主机挂载、绝对持久）。
     * DuckDB 第二连接(duplicate)的近期提交跨重启会丢（CHECKPOINT 受旧事务快照所限也救不回），
     * 故每次改动后另存一份权威 JSON，启动时回灌。调用方已持 {@code synchronized(conn)}。
     */
    private void persistMetaToFile() {
        var rows = new ArrayList<Map<String, Object>>();
        try (var st = conn.createStatement();
                var rs = st.executeQuery(
                        "SELECT rank_key,label,dynamic,visible,hidden,prev_season,brawl,same_zone,categories,first_seen,updated_at"
                                + ",partition_count,season_end_at"
                                + " FROM rank_key_meta")) {
            var md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                var m = new LinkedHashMap<String, Object>();
                for (int c = 1; c <= cols; c++) {
                    m.put(md.getColumnLabel(c), rs.getObject(c));
                }
                rows.add(m);
            }
        } catch (SQLException e) {
            log.warn("读取 rank_key_meta 失败，跳过 JSON 镜像: {}", e.getMessage());
            return;
        }
        try {
            Path tmp = metaFile.resolveSibling("rank_key_meta.json.tmp");
            Files.write(tmp, mapper.writeValueAsBytes(rows));
            Files.move(tmp, metaFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[meta诊断] 已写 JSON 镜像 {} 个 key -> {}", rows.size(), metaFile);
        } catch (Exception e) {
            log.warn("写 rank_key_meta JSON 镜像失败: {}", e.getMessage());
        }
    }

    /** 启动时从 JSON 镜像回灌 rank_key_meta（权威覆盖）。调用方已持 {@code synchronized(conn)}。 */
    @SuppressWarnings("unchecked")
    private void loadMetaFromFile() {
        if (!Files.exists(metaFile)) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(Files.readAllBytes(metaFile), List.class);
        } catch (Exception e) {
            log.warn("读 rank_key_meta JSON 镜像失败: {}", e.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int n = 0;
        try {
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO rank_key_meta(rank_key,label,dynamic,visible,hidden,prev_season,brawl,same_zone,categories,first_seen,updated_at)"
                    + ",partition_count,season_end_at"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (rank_key) DO UPDATE SET"
                    + " label=excluded.label, dynamic=excluded.dynamic, visible=excluded.visible,"
                    // hidden/brawl 是后台配置(setHidden/setBrawl)，源数据没有 → 刷新时一律保留库内现值，
                    // 否则每次 meta re-fetch 会把后台设的 hidden=true/brawl=true 覆盖回 false，隐藏预测失效。
                    + " prev_season=COALESCE(NULLIF(excluded.prev_season,''), rank_key_meta.prev_season),"
                    + " same_zone=COALESCE(NULLIF(excluded.same_zone,''), rank_key_meta.same_zone),"
                    + " categories=excluded.categories,"
                    + " partition_count=excluded.partition_count, season_end_at=excluded.season_end_at,"
                    + " updated_at=excluded.updated_at")) {
                for (var m : rows) {
                    String rk = m.get("rank_key") == null ? null : m.get("rank_key").toString();
                    if (rk == null || rk.isBlank()) {
                        continue;
                    }
                    ps.setString(1, rk);
                    ps.setString(2, m.get("label") == null ? null : m.get("label").toString());
                    ps.setBoolean(3, truthy(m.get("dynamic"), false));
                    ps.setBoolean(4, truthy(m.get("visible"), true));
                    ps.setBoolean(5, truthy(m.get("hidden"), false));
                    ps.setString(6, m.get("prev_season") == null ? null : m.get("prev_season").toString());
                    ps.setBoolean(7, truthy(m.get("brawl"), false));
                    ps.setString(8, m.get("same_zone") == null ? null : m.get("same_zone").toString());
                    ps.setString(9, categoriesJsonFromAny(m.get("categories"), truthy(m.get("brawl"), false)));
                    ps.setLong(10, longOr(m.get("first_seen")));
                    ps.setLong(11, longOr(m.get("updated_at")));
                    ps.setInt(12, clampPartitionCount((int) longOrDefault(m.get("partition_count"), MAX_SLICES)));
                    ps.setLong(13, longOr(m.get("season_end_at")));
                    ps.addBatch();
                    knownKeys.add(rk);
                    n++;
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
            log.warn("回灌 rank_key_meta 失败: {}", e.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
        }
        log.info("从 JSON 镜像回灌 rank_key_meta {} 个 key", n);
    }

    private static boolean truthy(Object o, boolean dft) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number num) {
            return num.intValue() != 0;
        }
        if (o instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return dft;
    }

    private static long longOr(Object o) {
        return longOrDefault(o, 0L);
    }

    private static long longOrDefault(Object o, long dft) {
        if (o instanceof Number num) {
            return num.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return dft;
            }
        }
        return dft;
    }

    private List<String> parseCategories(String raw, boolean brawl) {
        var out = new ArrayList<String>();
        if (raw != null && !raw.isBlank()) {
            try {
                Object parsed = mapper.readValue(raw, Object.class);
                if (parsed instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null) {
                            out.add(item.toString());
                        }
                    }
                } else {
                    out.add(raw);
                }
            } catch (Exception ignore) {
                for (String part : raw.split("[,，]")) {
                    out.add(part);
                }
            }
        }
        if (brawl) {
            out.add("乱斗榜");
        }
        return normalizeCategories(out);
    }

    private static List<String> normalizeCategories(List<String> categories) {
        var out = new ArrayList<String>();
        if (categories == null) {
            return out;
        }
        var seen = new java.util.LinkedHashSet<String>();
        for (String c : categories) {
            if (c == null) {
                continue;
            }
            String v = c.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (seen.add(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private String categoriesJsonFromAny(Object raw, boolean brawl) {
        var out = new ArrayList<String>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        } else if (raw != null) {
            out.add(raw.toString());
        }
        if (brawl) {
            out.add("乱斗榜");
        }
        try {
            return mapper.writeValueAsString(normalizeCategories(out));
        } catch (Exception e) {
            return "[]";
        }
    }

    private void execMeta(String sql, String value, String rankKey) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, value);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, rankKey);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("更新 rank_key_meta 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
            persistMetaToFile();
        }
    }

    private void execMeta(String sql, int value, String rankKey) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, value);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, rankKey);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("更新 rank_key_meta 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
            persistMetaToFile();
        }
    }

    private void execMeta(String sql, long value, String rankKey) {
        synchronized (conn) {
            try {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, value);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, rankKey);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignore) { /* best-effort */ }
                throw new IllegalStateException("更新 rank_key_meta 失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignore) { /* best-effort */ }
            }
            persistMetaToFile();
        }
    }

    private static int clampPartitionCount(int n) {
        return Math.max(1, Math.min(MAX_SLICES, n <= 0 ? MAX_SLICES : n));
    }

    /** 整体进度：已覆盖的 (账号×服务器) 来源数、服务器数、rankKey 数、最后更新时间。 */
    public Progress progress() {
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT COUNT(DISTINCT account_id||'@'||server), COUNT(DISTINCT server),"
                            + " COUNT(DISTINCT rank_key), COALESCE(MAX(updated_at),0) FROM rank_slice_group")) {
                if (rs.next()) {
                    return new Progress(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), keyCount());
                }
            } catch (SQLException e) {
                log.warn("progress 失败: {}", e.getMessage());
            }
        }
        return new Progress(0, 0, 0, 0, 0);
    }

    public long keyCount() {
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*) FROM rank_key_meta")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                return 0L;
            }
        }
    }

    /** 某 (账号,服务器) 是否已采集过（用于增量调度，避免重复）。 */
    public boolean hasSource(String accountId, String server) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM rank_slice_group WHERE account_id=? AND server=? LIMIT 1")) {
                ps.setString(1, accountId);
                ps.setString(2, server);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /**
     * 该 (账号×区) 是否在 {@code sinceTs} 之后采集过（rank_slice_group.updated_at &gt;= sinceTs）。
     * 发现服务用它判断：新 rankKey 出现后，这个来源是否已被重探过（map 已含新榜）；否则需重探补新榜。
     */
    public boolean hasFreshSource(String accountId, String server, long sinceTs) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM rank_slice_group WHERE account_id=? AND server=? AND updated_at>=? LIMIT 1")) {
                ps.setString(1, accountId);
                ps.setString(2, server);
                ps.setLong(3, sinceTs);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** 最新出现的 rankKey 的首次发现时间（rank_key_meta.first_seen 最大值）；无则 0。供发现服务判断哪些来源 map 已过期需重探。 */
    public long newestKeyFirstSeen() {
        synchronized (conn) {
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT MAX(first_seen) FROM rank_key_meta")) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            } catch (SQLException e) {
                return 0L;
            }
        }
        return 0L;
    }

    public record KeyInfo(String rankKey, String label, boolean dynamic, boolean visible,
            int sources, int maxSlices, long lastFetch, boolean hidden, String prevSeason, boolean brawl,
            String sameZone, List<String> categories, int partitionCount, long seasonEndAt) {}

    public record Progress(long sources, long servers, long rankKeys, long lastUpdate, long metaKeys) {}
}
