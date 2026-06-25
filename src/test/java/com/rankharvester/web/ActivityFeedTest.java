package com.rankharvester.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rankharvester.activity.ActivityBroadcastService;
import com.rankharvester.activity.ActivityEmailService;
import com.rankharvester.activity.ActivityFetcher;
import com.rankharvester.activity.ActivityImageRenderService;
import com.rankharvester.activity.ActivityRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 机器人轮询 feed 的游标逻辑单测（不触网、不发邮件）：基线不推、增量按 id 升序、分页 hasMore、nextSince 推进。
 */
class ActivityFeedTest {

    /** url 留空 → hasImage=false，避免触发后台渲染线程，专注游标逻辑。 */
    private static ActivityRow row(long id) {
        return new ActivityRow(id, null, "活动" + id, "<b>desc</b>" + id, null, "2026-06-01", "2026-06-30");
    }

    private static ActivityPublicController controllerWith(List<ActivityRow> rows) {
        var broadcast = mock(ActivityBroadcastService.class);
        when(broadcast.getOrFetchBundle()).thenReturn(new ActivityFetcher.FetchBundle(rows, "", ""));
        when(broadcast.lastFetchAt()).thenReturn(123L);
        when(broadcast.nextDetectAt()).thenReturn(456L);
        return new ActivityPublicController(broadcast, mock(ActivityImageRenderService.class),
                mock(ActivityEmailService.class), mock(CaptchaService.class), "https://example.com");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> acts(Map<String, Object> resp) {
        return (List<Map<String, Object>>) resp.get("activities");
    }

    @Test
    void baseline_returns_latest_newest_first_and_marks_baseline_without_pushing() {
        var c = controllerWith(List.of(row(100), row(101), row(102), row(103)));
        Map<String, Object> r = c.feed(null, 20);

        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("isBaseline")).isEqualTo(true);
        assertThat(r.get("nextSince")).isEqualTo(103L); // 全局最大 id
        // 新→旧预览
        assertThat(acts(r)).extracting(m -> m.get("activityId")).containsExactly(103L, 102L, 101L, 100L);
        // 绝对链接 + 纯文本
        assertThat(acts(r).get(0).get("descriptionText")).isEqualTo("desc103");
    }

    @Test
    void incremental_returns_only_newer_ids_ascending() {
        var c = controllerWith(List.of(row(100), row(101), row(102), row(103)));
        Map<String, Object> r = c.feed(101L, 20);

        assertThat(r.get("isBaseline")).isEqualTo(false);
        assertThat(r.get("hasMore")).isEqualTo(false);
        assertThat(r.get("nextSince")).isEqualTo(103L);
        assertThat(acts(r)).extracting(m -> m.get("activityId")).containsExactly(102L, 103L); // 升序
    }

    @Test
    void incremental_paginates_with_hasMore_and_advances_cursor_by_page_max() {
        var c = controllerWith(List.of(row(1), row(2), row(3), row(4), row(5)));
        Map<String, Object> r = c.feed(0L, 2);

        assertThat(r.get("hasMore")).isEqualTo(true);
        assertThat(acts(r)).extracting(m -> m.get("activityId")).containsExactly(1L, 2L);
        assertThat(r.get("nextSince")).isEqualTo(2L); // 进到本页最大 id，下次再取剩余

        Map<String, Object> r2 = c.feed(2L, 2);
        assertThat(acts(r2)).extracting(m -> m.get("activityId")).containsExactly(3L, 4L);
        assertThat(r2.get("hasMore")).isEqualTo(true); // 还剩 5（3 条 > 上限 2），继续轮询
        assertThat(r2.get("nextSince")).isEqualTo(4L);

        Map<String, Object> r3 = c.feed(4L, 2);
        assertThat(acts(r3)).extracting(m -> m.get("activityId")).containsExactly(5L);
        assertThat(r3.get("hasMore")).isEqualTo(false); // 取尽
        assertThat(r3.get("nextSince")).isEqualTo(5L);
    }

    @Test
    void incremental_no_new_returns_empty_and_keeps_since() {
        var c = controllerWith(List.of(row(10), row(11)));
        Map<String, Object> r = c.feed(11L, 20);

        assertThat(acts(r)).isEmpty();
        assertThat(r.get("hasMore")).isEqualTo(false);
        assertThat(r.get("nextSince")).isEqualTo(11L); // 无新活动则游标不动
    }
}
