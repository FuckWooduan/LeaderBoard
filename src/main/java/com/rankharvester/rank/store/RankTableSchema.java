package com.rankharvester.rank.store;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按榜形分表的 DuckDB schema。
 *
 * <p>固定三表：{@code reduced_rank} / {@code team_rank} / {@code ladder_rank}；
 * 分区静态榜按 rankKey 一表一个：{@code partition_rank_<rankKey>}（{@link #ensurePartitionTable}）。
 * 字段全部拍平、不存原始 bean；嵌套以 {@code info_*}/{@code score_*}/{@code rpg_team_*} 前缀命名。
 *
 * <p>每行都带 scope/snapshot 列：{@code leaderboard_code, server_id, snapshot_id, fetched_at, rank}。
 * 只留最新一份：写入时按 {@code leaderboard_code} 整份覆盖（由各 store 实现）。
 */
@Component
public class RankTableSchema {

    private static final Logger log = LoggerFactory.getLogger(RankTableSchema.class);
    private static final Pattern RANK_KEY_OK = Pattern.compile("[A-Za-z0-9_]+");

    private final Connection conn;

    public RankTableSchema(Connection duckDbConnection) {
        this.conn = duckDbConnection;
    }

    private static final String REDUCED_COLS =
            """
            (
                leaderboard_code         VARCHAR,
                server_id                INTEGER,
                snapshot_id              BIGINT,
                fetched_at               BIGINT,
                rank                     INTEGER,
                rank_type                INTEGER,
                last_rank                BIGINT,
                dateline                 BIGINT,
                update_display_count     INTEGER,
                rank_id                  BIGINT,
                display                  BOOLEAN,
                unique_id_bean           BIGINT,
                last_change_display_flag BIGINT,
                info_team_name           VARCHAR,
                info_blue_vip_type       INTEGER,
                info_login_id            BIGINT,
                info_douwa_vip           INTEGER,
                info_homeland_open       BOOLEAN,
                info_char_id             BIGINT,
                info_lv                  INTEGER,
                info_char_name           VARCHAR,
                info_operator_id         INTEGER,
                info_blue_vip_level      INTEGER,
                score_all_number         BIGINT,
                score_number             BIGINT,
                score_dateline           BIGINT,
                score_gc_id              BIGINT,
                score_rebirth            INTEGER
            )""";

    private static final String TEAM_COLS =
            """
            (
                leaderboard_code     VARCHAR,
                server_id            INTEGER,
                snapshot_id          BIGINT,
                fetched_at           BIGINT,
                rank                 INTEGER,
                team_id              BIGINT,
                team_name            VARCHAR,
                leader_name          VARCHAR,
                team_lv              INTEGER,
                team_exp             BIGINT,
                m_number             INTEGER,
                m_number_limit       INTEGER,
                declaration          VARCHAR,
                rpg_team_capacity    INTEGER,
                rpg_team_lv          INTEGER,
                rpg_team_number      INTEGER,
                rpg_team_total_bonus BIGINT,
                rpg_team_declaration VARCHAR
            )""";

    private static final String LADDER_COLS =
            """
            (
                leaderboard_code VARCHAR,
                server_id        INTEGER,
                snapshot_id      BIGINT,
                fetched_at       BIGINT,
                rank             INTEGER,
                team_name        VARCHAR,
                last_rank        INTEGER,
                medal_index      INTEGER,
                login_id         BIGINT,
                char_id          BIGINT,
                lv               INTEGER,
                user_name        VARCHAR,
                char_name        VARCHAR,
                extra_value      BIGINT,
                medal_line       INTEGER,
                cur_rank         INTEGER,
                number           BIGINT,
                team_id          BIGINT,
                operator_id      INTEGER
            )""";

    @PostConstruct
    public void init() throws SQLException {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                // 一次性迁移：早期版本 reduced_rank 带主键 (leaderboard_code, score_gc_id)，
                // 现改批量 append（新旧快照同 gcId 会触发主键冲突），故检测到旧主键即 DROP 重建（只丢 reduced 数据，会重抓）。
                if (hasPrimaryKey(st, "reduced_rank")) {
                    st.execute("DROP TABLE reduced_rank");
                    log.warn("检测到旧版 reduced_rank 主键，已 DROP 重建为无主键 append 表（reduced 数据将重抓）");
                }
                // 不建 leaderboard_code 二级索引：DuckDB 是列式库，按 code 过滤全扫描即毫秒级；
                // 而其 ART 二级索引在 DELETE 时有「Failed to delete all rows from index」致命 bug（会使整库失效），故一律不用。
                st.execute("CREATE TABLE IF NOT EXISTS reduced_rank" + REDUCED_COLS);
                // 迁移：last_change_display_flag 是毫秒时间戳(long)，老表 INTEGER 会溢出，加宽为 BIGINT。
                try {
                    st.execute("ALTER TABLE reduced_rank ALTER last_change_display_flag TYPE BIGINT");
                } catch (SQLException ignore) { /* 已是 BIGINT 或不支持则忽略 */ }
                // team/ladder 仍走「正式表 + _staging 影子表」：分页写 staging，抓满后 promote 原子换入（整份覆盖）。
                createReal(st, "team_rank", TEAM_COLS);
                createReal(st, "ladder_rank", LADDER_COLS);
                // 清掉早期版本可能残留的二级索引（避免命中 DuckDB 删索引 bug）。
                for (String ix : new String[]{"idx_reduced_code", "idx_reduced_code_gc", "idx_team_code",
                        "idx_team_code_stg", "idx_ladder_code", "idx_ladder_code_stg"}) {
                    try { st.execute("DROP INDEX IF EXISTS " + ix); } catch (SQLException ignore) { /* 无则忽略 */ }
                }

                // 断点续传检查点：每个榜在抓取中的 snapshot_id / 下一页 offset / 服务端 total。
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS rank_progress(
                            leaderboard_code VARCHAR PRIMARY KEY,
                            snapshot_id      BIGINT,
                            next_offset      INTEGER,
                            total            INTEGER,
                            updated_at       BIGINT
                        )""");

                // 武器库（来自 GameData 的 shop_weapon + shop_weapon_property 面板）；每次同步全量替换，无主键/索引。
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS weapon(
                            id          BIGINT,
                            name        VARCHAR,
                            code_name   VARCHAR,
                            type        INTEGER,
                            sub_type    INTEGER,
                            type_label  VARCHAR,
                            description VARCHAR,
                            specific    VARCHAR,
                            tags        VARCHAR,
                            icon        VARCHAR,
                            penetrate   DOUBLE,
                            fire_rate   DOUBLE,
                            power       DOUBLE,
                            accurate    DOUBLE,
                            stability   DOUBLE,
                            weight      DOUBLE,
                            bullet      DOUBLE,
                            bullet_max  DOUBLE,
                            search_all  VARCHAR,
                            updated_at  BIGINT
                        )""");
                // 旧库迁移：补 search_all 列
                try {
                    st.execute("ALTER TABLE weapon ADD COLUMN IF NOT EXISTS search_all VARCHAR");
                } catch (SQLException ignore) {
                    // 列已存在或不支持，忽略
                }

                // 武器相关辅助表的通用可搜索条目（武器技能/配件槽位/配件/配件技能/重构词缀）；
                // name/detail 保留 HTML 供展示，search_text 去 HTML 供模糊匹配。每次同步全量替换。
                st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS weapon_entry(
                            category     VARCHAR,
                            cat_label    VARCHAR,
                            key          VARCHAR,
                            name         VARCHAR,
                            detail       VARCHAR,
                            search_text  VARCHAR,
                            payload_json VARCHAR,
                            updated_at   BIGINT
                        )""");
                try {
                    st.execute("ALTER TABLE weapon_entry ADD COLUMN IF NOT EXISTS payload_json VARCHAR");
                } catch (SQLException ignore) {
                    // 列已存在或不支持，忽略
                }
            }
        }
        log.info("DuckDB 分榜表已就绪：reduced_rank / team_rank / ladder_rank（含 _staging 影子表与 rank_progress 检查点）");
    }

    /** 该表当前是否带主键约束（用于一次性迁移检测）。元数据不可用时按「无主键」处理（安全）。 */
    private static boolean hasPrimaryKey(java.sql.Statement st, String table) {
        try (var rs = st.executeQuery(
                "SELECT COUNT(*) FROM duckdb_constraints() WHERE table_name = '" + table
                        + "' AND constraint_type = 'PRIMARY KEY'")) {
            return rs.next() && rs.getLong(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 建正式表 + 同构 _staging 影子表（不建二级索引，避免 DuckDB 删索引致命 bug）。 */
    private static void createReal(java.sql.Statement st, String table, String cols) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS " + table + cols);
        st.execute("CREATE TABLE IF NOT EXISTS " + table + "_staging" + cols);
    }

    /** 分区静态榜表名（一 rankKey 一表）。 */
    public static String partitionTableName(String rankKey) {
        if (rankKey == null || !RANK_KEY_OK.matcher(rankKey).matches()) {
            throw new IllegalArgumentException("非法 rankKey（仅允许字母数字下划线）: " + rankKey);
        }
        return "partition_rank_" + rankKey;
    }

    /** 按需创建某 rankKey 的分区静态榜表，返回表名。 */
    public String ensurePartitionTable(String rankKey) {
        String table = partitionTableName(rankKey);
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute(
                        "CREATE TABLE IF NOT EXISTS " + table + "("
                        + "leaderboard_code VARCHAR,"
                        + "rank_key VARCHAR,"
                        + "partition_id INTEGER,"
                        + "server_id INTEGER,"
                        + "snapshot_id BIGINT,"
                        + "fetched_at BIGINT,"
                        + "rank INTEGER,"
                        + "row_index INTEGER,"
                        + "rank_bean_class VARCHAR,"
                        + "last_rank BIGINT,"
                        + "dateline BIGINT,"
                        + "update_display_count INTEGER,"
                        + "rank_id BIGINT,"
                        + "display BOOLEAN,"
                        + "unique_id_bean VARCHAR,"
                        + "last_change_display_flag BIGINT,"
                        + "info_class VARCHAR,"
                        + "info_team_name VARCHAR,"
                        + "info_login_id BIGINT,"
                        + "info_char_id BIGINT,"
                        + "info_lv BIGINT,"
                        + "info_char_name VARCHAR,"
                        + "info_operator_id INTEGER,"
                        + "info_bonus BIGINT,"
                        + "info_team_id BIGINT,"
                        + "info_last_rank BIGINT,"
                        + "info_blue_vip_type INTEGER,"
                        + "info_all_number BIGINT,"
                        + "info_medal_index INTEGER,"
                        + "info_douwa_vip INTEGER,"
                        + "info_update_display_count INTEGER,"
                        + "info_display BOOLEAN,"
                        + "info_last_change_display_flag BIGINT,"
                        + "info_extra_value BIGINT,"
                        + "info_medal_line BIGINT,"
                        + "info_cur_rank BIGINT,"
                        + "info_number BIGINT,"
                        + "info_vip_lv INTEGER,"
                        + "info_blue_vip_level INTEGER,"
                        + "score_class VARCHAR,"
                        + "score_number DOUBLE,"
                        + "score_extra_number DOUBLE,"
                        + "score_dateline BIGINT,"
                        + "score_gc_id VARCHAR,"
                        + "score_rebirth INTEGER)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_code ON " + table + "(leaderboard_code)");
            } catch (SQLException e) {
                throw new IllegalStateException("创建分区表失败: " + table, e);
            }
        }
        return table;
    }
}
