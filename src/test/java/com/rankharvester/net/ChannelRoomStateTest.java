package com.rankharvester.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 房间态 {@link ChannelRoomState} 测试：整表重置(轮巡) + add/change/delete 增量(驻留) + 变更广播。
 * 调度器 {@link RoomScheduler} 的「号够驻留 / 号不够轮巡」逻辑依赖真实连接，归 dry-run/真机验证；
 * 这里只覆盖与连接无关的快照维护与广播（纯内存、确定性）。
 */
class ChannelRoomStateTest {

    private static Map<String, Object> room(double id, String name) {
        var r = new LinkedHashMap<String, Object>();
        r.put("roomId", id);
        r.put("roomName", name);
        return r;
    }

    @Test
    void replaceAll_resetsSnapshot_andBroadcasts() {
        var st = new ChannelRoomState("9", 119);
        var fires = new AtomicInteger();
        st.setBroadcaster(s -> fires.incrementAndGet());

        st.replaceAll(List.of(room(1, "A"), room(2, "B")));
        assertEquals(2, st.snapshot().size());
        assertEquals(1, fires.get());

        st.replaceAll(List.of(room(3, "C"))); // 轮巡再刷 → 整表换
        assertEquals(1, st.snapshot().size());
        assertEquals(3.0, ChannelRoomState.roomId(st.snapshot().get(0)));
        assertEquals(2, fires.get());
    }

    @Test
    void upsert_and_remove_maintainSnapshot_andBroadcast() {
        var st = new ChannelRoomState("9", 119);
        var fires = new AtomicInteger();
        st.setBroadcaster(s -> fires.incrementAndGet());
        st.replaceAll(List.of(room(1, "A"), room(2, "B")));
        fires.set(0);

        st.upsert(room(3, "C"));                 // add
        assertEquals(3, st.snapshot().size());
        st.upsert(room(1, "A改"));               // change
        assertEquals(3, st.snapshot().size());
        assertEquals("A改", st.snapshot().stream()
                .filter(r -> ChannelRoomState.roomId(r) == 1.0).findFirst().orElseThrow().get("roomName"));
        st.remove(2.0);                          // delete
        assertEquals(2, st.snapshot().size());
        assertTrue(st.snapshot().stream().noneMatch(r -> ChannelRoomState.roomId(r) == 2.0));
        assertEquals(3, fires.get());            // add/change/delete 各广播一次
    }

    @Test
    void subscribers_trackedAndCleared() {
        var st = new ChannelRoomState("9", 119);
        assertTrue(!st.hasSubscribers());
        var em = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        st.addSubscriber(em);
        assertTrue(st.hasSubscribers());
        st.removeSubscriber(em);
        assertTrue(!st.hasSubscribers());
    }
}
