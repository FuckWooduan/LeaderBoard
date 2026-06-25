package com.rankharvester.rank.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankTableSchemaTest {

    @Test
    void creates_fixed_and_partition_tables_with_expected_columns() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            var schema = new RankTableSchema(conn);
            schema.init();

            assertThat(columns(conn, "reduced_rank"))
                    .contains("leaderboard_code", "rank_type", "info_char_id", "score_number", "score_rebirth");
            assertThat(columns(conn, "team_rank"))
                    .contains("team_id", "team_exp", "m_number_limit", "rpg_team_total_bonus");
            assertThat(columns(conn, "ladder_rank"))
                    .contains("team_name", "char_name", "medal_index", "extra_value", "cur_rank", "number")
                    .doesNotContain("subject_id");

            String table = schema.ensurePartitionTable("2037001");
            assertThat(table).isEqualTo("partition_rank_2037001");
            assertThat(columns(conn, table))
                    .contains("rank_key", "partition_id", "info_class", "info_bonus", "score_number", "score_extra_number");
        }
    }

    @Test
    void rejects_illegal_rank_key() {
        assertThatThrownBy(() -> RankTableSchema.partitionTableName("2037001; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> columns(Connection conn, String table) throws Exception {
        var cols = new ArrayList<String>();
        try (var rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME"));
            }
        }
        return cols;
    }
}
