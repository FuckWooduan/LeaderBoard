package com.rankharvester.rank.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rankharvester.apc.ApcObject;
import com.rankharvester.net.GameConnection;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 断点续传：战队榜分页中途失败后，重抓从 next_offset 续传而非从头，最终抓满并 promote。 */
class TeamRankResumeTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final int TOTAL = 40; // 5 页 × 8 条
    private static final int PAGE = 8;

    @Test
    void resumes_from_checkpoint_after_midway_failure() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            new RankTableSchema(conn).init();
            var teamStore = new TeamRankStore(conn);
            var resume = new RankResumeStore(conn);
            var router = new RankFetchRouter(
                    new ReducedRankParser(), new LadderRankParser(), new TeamRankParser(),
                    new ReducedRankStore(conn), new LadderRankStore(conn), teamStore, resume,
                    0L, 1000, 50000);

            var def = new LeaderboardDef(
                    "TEAM:13", RankType.TEAM, RankKind.TEAM, "demo", "13", 0, List.of(0), true);

            // 第一次：第 3 页（offset=16）抛错 → 整榜失败、不 promote。
            var conn1 = new FakeTeamConn(16);
            assertThatThrownBy(() -> router.fetchAndStore(conn1, def, T)).isInstanceOf(RuntimeException.class);

            // 正式表仍为空（没写半截），但已抓的前两页落在 staging，检查点记录 next_offset=16。
            assertThat(teamStore.count("TEAM:13")).isZero();
            var prog = resume.loadProgress("TEAM:13");
            assertThat(prog).isPresent();
            assertThat(prog.get().nextOffset()).isEqualTo(16);
            assertThat(prog.get().total()).isEqualTo(TOTAL);

            // 第二次：不再注入失败 → 从 offset=16 续抓到满 → promote。
            var conn2 = new FakeTeamConn(-1);
            int rows = router.fetchAndStore(conn2, def, T);

            assertThat(rows).isEqualTo(TOTAL);
            assertThat(teamStore.count("TEAM:13")).isEqualTo(TOTAL);
            // 续抓时 conn2 只请求了 offset 16/24/32（3 页），未重抓前两页。
            assertThat(conn2.requestedOffsets).containsExactly(16, 24, 32);
            // 检查点已清除。
            assertThat(resume.loadProgress("TEAM:13")).isEmpty();

            // rank 连续 1..40（前两页来自 conn1、后三页来自 conn2，promote 后合并）。
            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT MIN(rank), MAX(rank), COUNT(DISTINCT rank) FROM team_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getInt(2)).isEqualTo(TOTAL);
                assertThat(rs.getInt(3)).isEqualTo(TOTAL);
            }
        }
    }

    /** 假连接：getTeamList(offset) 每页返回 8 条（offset≥TOTAL 返回空），可在指定 offset 注入一次失败。 */
    private static final class FakeTeamConn implements GameConnection {
        private final int failAtOffset;
        final List<Integer> requestedOffsets = new ArrayList<>();

        FakeTeamConn(int failAtOffset) {
            this.failAtOffset = failAtOffset;
        }

        @Override
        public ApcObject call(String requestFunction, String expectCallback, Duration timeout, Object... params) {
            int offset = ((Number) params[0]).intValue();
            requestedOffsets.add(offset);
            if (offset == failAtOffset) {
                throw new IllegalStateException("等待回包失败/超时: onGetTeamList");
            }
            var teams = new ArrayList<Object>();
            for (int i = offset; i < Math.min(offset + PAGE, TOTAL); i++) {
                var bean = new LinkedHashMap<String, Object>();
                bean.put("teamId", 1000L + i);
                bean.put("name", "队" + i);
                bean.put("leaderName", "长" + i);
                bean.put("teamLv", 6);
                bean.put("teamExp", 100L * (TOTAL - i));
                bean.put("mNumber", 50);
                bean.put("mNumberLimit", 100);
                teams.add(bean);
            }
            var apc = new ApcObject();
            apc.setFunctionName(expectCallback);
            apc.setParameters(List.of(teams, TOTAL, 0));
            return apc;
        }

        @Override
        public String accountId() {
            return "demo";
        }

        @Override
        public String server() {
            return "13";
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void send(String requestFunction, Object... params) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
