package com.rankharvester.rank.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankKind;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.model.rows.ReducedRankRow;
import com.rankharvester.rank.store.BoardStateStore;
import com.rankharvester.rank.store.LeaderboardCatalog;
import com.rankharvester.rank.store.RankTableSchema;
import com.rankharvester.rank.store.ReducedRankStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicRankServiceTest {

    private static final String CODE = "REDUCED:331:13";

    private static LeaderboardDef def() {
        return new LeaderboardDef(CODE, RankType.REDUCED, RankKind.EXP, "acct", "13", 0,
                List.of(331, 0, 10000, 0), true);
    }

    private static ReducedRankRow row(int rank) {
        return new ReducedRankRow(rank, 331, (long) rank, 1L, 0, 0L, true, 1000L + rank, 0L,
                "队", 0, 52L, 0, true, 1000L + rank, 600, "玩家" + rank, 1, 0,
                13L, (long) (1000 - rank) * 100, 1L, 9999L + rank, 0);
    }

    @Test
    void paginates_public_board_with_labels_and_gates_unpublished() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            new RankTableSchema(conn).init();
            var stateStore = new BoardStateStore(conn);
            stateStore.init();
            var boardState = new BoardStateService(stateStore);
            boardState.load();

            // 写 150 行
            var reduced = new ReducedRankStore(conn);
            var rows = new ArrayList<ReducedRankRow>();
            for (int i = 1; i <= 150; i++) rows.add(row(i));
            reduced.appendPage(CODE, 13, 1L, 1L, rows);

            var catalog = mock(LeaderboardCatalog.class);
            when(catalog.byCode(CODE)).thenReturn(Optional.of(def()));
            when(catalog.all()).thenReturn(List.of(def()));

            var svc = new PublicRankService(conn, catalog, boardState);

            // 有数据即可见（无需手动开放）
            assertThat(svc.publicBoards()).extracting(PublicRankService.BoardBrief::code).containsExactly(CODE);

            var page1 = svc.page(CODE, 1);
            assertThat(page1.total()).isEqualTo(150);
            assertThat(page1.size()).isEqualTo(100);
            assertThat(page1.rows()).hasSize(100);
            // 字段配置：rank 强制第一 + 预置默认的可见列（角色名/等级/战队/经验/区）
            assertThat(page1.fields()).extracting(PublicRankService.FieldView::key)
                    .startsWith("rank").contains("info_char_name", "score_number");
            assertThat(page1.fields()).filteredOn(f -> f.key().equals("info_char_name"))
                    .extracting(PublicRankService.FieldView::label).containsExactly("角色名");
            assertThat(page1.rows().getFirst()).containsEntry("rank", 1).containsEntry("info_char_name", "玩家1");

            var page2 = svc.page(CODE, 2);
            assertThat(page2.rows()).hasSize(50);
            assertThat(page2.rows().getFirst().get("rank")).isEqualTo(101);
        }
    }
}
