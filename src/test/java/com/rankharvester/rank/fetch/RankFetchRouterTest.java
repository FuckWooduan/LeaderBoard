package com.rankharvester.rank.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.rankharvester.account.GameAccount;
import com.rankharvester.net.GameConnection;
import com.rankharvester.net.SimulatedGameClient;
import com.rankharvester.rank.fetch.parse.LadderRankParser;
import com.rankharvester.rank.fetch.parse.ReducedRankParser;
import com.rankharvester.rank.fetch.parse.TeamRankParser;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankKind;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.store.LadderRankStore;
import com.rankharvester.rank.store.RankResumeStore;
import com.rankharvester.rank.store.RankTableSchema;
import com.rankharvester.rank.store.ReducedRankStore;
import com.rankharvester.rank.store.TeamRankStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** dry-run 端到端（无 Redis）：模拟连接 → 路由按榜形解析 → 落到正确的表。 */
class RankFetchRouterTest {

    private static final Duration T = Duration.ofSeconds(5);

    @Test
    void routes_reduced_and_ladder_into_their_tables() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            new RankTableSchema(conn).init();
            var reducedStore = new ReducedRankStore(conn);
            var ladderStore = new LadderRankStore(conn);
            var teamStore = new TeamRankStore(conn);
            var resume = new RankResumeStore(conn);
            var router = new RankFetchRouter(
                    new ReducedRankParser(), new LadderRankParser(), new TeamRankParser(),
                    reducedStore, ladderStore, teamStore, resume,
                    0L, 50, 50000);

            var client = new SimulatedGameClient();
            GameConnection gc = client.connectAndLogin(
                    new GameAccount("demo", "u", "p", List.of("13")), "13");

            var reduced = new LeaderboardDef(
                    "REDUCED:331:13", RankType.REDUCED, RankKind.EXP, "demo", "13", 0,
                    List.of(331, 0, 10000, 0), true);
            var ladder = new LeaderboardDef(
                    "LADDER:23:13", RankType.LADDER, RankKind.CURRENT_SEASON, "demo", "13", 0,
                    List.of(23, List.of(4), 0, 50, 0), true);

            int reducedRows = router.fetchAndStore(gc, reduced, T);
            int ladderRows = router.fetchAndStore(gc, ladder, T);

            assertThat(reducedRows).isEqualTo(250);
            assertThat(ladderRows).isEqualTo(250);
            assertThat(reducedStore.count("REDUCED:331:13")).isEqualTo(250);
            assertThat(ladderStore.count("LADDER:23:13")).isEqualTo(250);

            // reduced 落到 reduced_rank、ladder 落到 ladder_rank（互不串表）
            assertThat(ladderStore.count("REDUCED:331:13")).isZero();
            assertThat(reducedStore.count("LADDER:23:13")).isZero();

            // 抽查列：rank_type、server_id、score、字符串解析
            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT rank_type, server_id, score_number FROM reduced_rank WHERE rank = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("rank_type")).isEqualTo(331);
                assertThat(rs.getInt("server_id")).isEqualTo(13);
                assertThat(rs.getLong("score_number")).isEqualTo(250L * 10000L);
            }
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT server_id, number FROM ladder_rank WHERE rank = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("server_id")).isEqualTo(13);
                assertThat(rs.getLong("number")).isEqualTo(250L * 100L);
            }

            gc.close();
        }
    }
}
