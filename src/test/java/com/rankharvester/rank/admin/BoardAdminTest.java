package com.rankharvester.rank.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rankharvester.rank.model.Lane;
import com.rankharvester.rank.model.LeaderboardDef;
import com.rankharvester.rank.model.RankKind;
import com.rankharvester.rank.model.RankType;
import com.rankharvester.rank.queue.RedisTaskQueue;
import com.rankharvester.rank.store.BoardStateStore;
import com.rankharvester.rank.store.LeaderboardCatalog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardAdminTest {

    private static LeaderboardDef def(String code, boolean enabled) {
        return new LeaderboardDef(code, RankType.REDUCED, RankKind.EXP, "acct", "13", 0,
                List.of(331, 0, 10000, 0), enabled);
    }

    @Test
    void enable_state_overrides_default_and_persists() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            var store = new BoardStateStore(conn);
            store.init();

            var svc = new BoardStateService(store);
            svc.load();

            var board = def("REDUCED:331:13", false); // 默认关（动态榜过时）
            assertThat(svc.isEnabled(board)).isFalse();
            assertThat(svc.hasEnabledOverride("REDUCED:331:13")).isFalse();

            svc.setEnabled("REDUCED:331:13", true, 1000L); // 后台手动开启
            assertThat(svc.isEnabled(board)).isTrue();
            assertThat(svc.hasEnabledOverride("REDUCED:331:13")).isTrue();

            // 新实例重新载入 → 覆盖已持久化
            var svc2 = new BoardStateService(store);
            svc2.load();
            assertThat(svc2.isEnabled(board)).isTrue();
        }
    }

    @Test
    void public_flag_and_field_labels_roundtrip() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            var store = new BoardStateStore(conn);
            store.init();
            var svc = new BoardStateService(store);
            svc.load();

            // 默认不开放
            assertThat(svc.isPublic("REDUCED:331:13")).isFalse();
            svc.setPublic("REDUCED:331:13", true, 1L);
            assertThat(svc.isPublic("REDUCED:331:13")).isTrue();

            // 字段名：默认中文 → 覆盖
            assertThat(svc.label("REDUCED:331:13", "info_char_name")).isEqualTo("角色名");
            svc.setLabel("REDUCED:331:13", "info_char_name", "大区角色");
            assertThat(svc.label("REDUCED:331:13", "info_char_name")).isEqualTo("大区角色");
            // 全字段表含全部 reduced 字段，且覆盖生效
            var labels = svc.labels("REDUCED:331:13",
                    com.rankharvester.rank.model.RankType.REDUCED);
            assertThat(labels).containsEntry("info_char_name", "大区角色")
                    .containsEntry("score_number", "经验") // REDUCED:331 组默认渲染名（seedDefaults）
                    .hasSize(24);

            // 持久化：新实例载入后仍在
            var svc2 = new BoardStateService(store);
            svc2.load();
            assertThat(svc2.isPublic("REDUCED:331:13")).isTrue();
            assertThat(svc2.label("REDUCED:331:13", "info_char_name")).isEqualTo("大区角色");
        }
    }

    @Test
    void manual_refresh_enqueues_into_highest_priority_lane() {
        var catalog = mock(LeaderboardCatalog.class);
        var queue = mock(RedisTaskQueue.class);
        when(catalog.byCode("REDUCED:331:13")).thenReturn(Optional.of(def("REDUCED:331:13", false)));

        var refresh = new ManualRefreshService(catalog, queue);
        String taskId = refresh.trigger("REDUCED:331:13", 5000L);

        assertThat(taskId).isEqualTo("REDUCED:331:13#manual#5000");
        verify(queue).enqueue(eq("REDUCED:331:13#manual#5000"), eq("REDUCED:331:13"), eq(Lane.MANUAL), eq(5000L));
    }

    @Test
    void manual_refresh_unknown_board_throws() {
        var catalog = mock(LeaderboardCatalog.class);
        var queue = mock(RedisTaskQueue.class);
        when(catalog.byCode("nope")).thenReturn(Optional.empty());

        var refresh = new ManualRefreshService(catalog, queue);
        assertThatThrownBy(() -> refresh.trigger("nope", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manual_lane_is_first_for_top_priority() {
        assertThat(Lane.values()[0]).isEqualTo(Lane.MANUAL);
        assertThat(Lane.MANUAL.starvationAfter().isZero()).isTrue();
    }
}
