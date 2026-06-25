package com.rankharvester.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rankharvester.net.ChannelRoomService;
import com.rankharvester.net.ChannelRoomService.ChannelView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 频道大厅 Controller 单测（纯 Mockito，不起 Spring 上下文/不需 Redis，与本仓库其它测试同风格）：
 * 验证 {@code channel-servers} 列举 + 可用标记，{@code channels} 的正常/未配号/失败三条返回路径。
 */
class ChannelRoomControllerTest {

    @Test
    void channelServers_listsDistrictsWithAvailabilityFlag() {
        var svc = mock(ChannelRoomService.class);
        when(svc.available("6")).thenReturn(true); // 电信一区(districtId=6) 配了侦察号
        var ctrl = new ChannelRoomController(svc);

        List<Map<String, Object>> list = ctrl.channelServers();

        assertThat(list).isNotEmpty();
        assertThat(list.get(0)).containsKeys("id", "name", "available");
        var six = list.stream().filter(m -> Integer.valueOf(6).equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(six.get("available")).isEqualTo(true);
    }

    @Test
    void channels_available_wrapsViews() {
        var svc = mock(ChannelRoomService.class);
        when(svc.channels("6")).thenReturn(List.of(
                new ChannelView(101, "一区电信1频道", 1400, 2000, 70, "crowd", 1, 999, 0, "s1f.x")));
        var ctrl = new ChannelRoomController(svc);

        Map<String, Object> out = ctrl.channels("6");

        assertThat(out.get("available")).isEqualTo(true);
        assertThat(out.get("server")).isEqualTo("6");
        @SuppressWarnings("unchecked")
        var views = (List<ChannelView>) out.get("channels");
        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("一区电信1频道");
        assertThat(views.get(0).state()).isEqualTo("crowd");
        assertThat(out).containsKey("ts");
    }

    @Test
    void channels_unconfiguredDistrict_returnsAvailableFalseWithMessage() {
        var svc = mock(ChannelRoomService.class);
        when(svc.channels("99")).thenThrow(new IllegalArgumentException("该区未配置侦察号: 99"));
        var ctrl = new ChannelRoomController(svc);

        Map<String, Object> out = ctrl.channels("99");

        assertThat(out.get("available")).isEqualTo(false);
        assertThat(out.get("message")).isEqualTo("该区未配置侦察号: 99");
    }

    @Test
    void channels_queryError_returnsAvailableFalse() {
        var svc = mock(ChannelRoomService.class);
        when(svc.channels("6")).thenThrow(new IllegalStateException("查询繁忙，请稍后再试"));
        var ctrl = new ChannelRoomController(svc);

        Map<String, Object> out = ctrl.channels("6");

        assertThat(out.get("available")).isEqualTo(false);
        assertThat(out.get("message").toString()).contains("频道查询失败");
    }
}
