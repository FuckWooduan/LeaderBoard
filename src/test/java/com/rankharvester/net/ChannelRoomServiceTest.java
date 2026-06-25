package com.rankharvester.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.rankharvester.gamedata.GameConfigNameService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 频道大厅协调层测试：DTO 映射（人数/容量/拥挤状态/进度）+ single-flight 合并（同区并发只一次实查询）。
 * 用假的 {@link ScoutConnectionService} 注入，不连真实/模拟游戏服、不起 Spring 上下文。
 */
class ChannelRoomServiceTest {

    /** 用假侦察连接 + mock 的名称服务装配被测服务（频道人数测试用不到名称服务）。 */
    private static ChannelRoomService svc(ScoutConnectionService scout) {
        return new ChannelRoomService(scout, mock(GameConfigNameService.class));
    }

    /** 假侦察连接：统计 channelList 被实际调用次数，可注入延时以制造并发重叠。 */
    private static final class FakeScout extends ScoutConnectionService {
        final AtomicInteger calls = new AtomicInteger();
        volatile long delayMs = 0;
        final List<Map<String, Object>> data;

        FakeScout(List<Map<String, Object>> data) {
            super(null, null, null, null, null); // 构造仅赋值，不触发任何登录；本类覆写了用到的方法
            this.data = data;
        }

        @Override
        public boolean available(String district) {
            return true;
        }

        @Override
        public List<Map<String, Object>> channelList(String district) {
            calls.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return data;
        }
    }

    private static Map<String, Object> channel(int id, String name, int number, int maxClient) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("number", number);
        m.put("maxClient", maxClient);
        m.put("limitMinLV", 1);
        m.put("limitMaxLV", 999);
        m.put("position", id);
        return m;
    }

    @Test
    void channels_mapsCountsPercentAndState() {
        var raw = List.of(
                channel(1, "空闲频道", 180, 1200),   // raw=180/900=0.2  → free,  pct=15
                channel(2, "较多频道", 1400, 2000),  // raw=1400/1700=0.82 → crowd, pct=70
                channel(3, "已满频道", 2000, 2000),  // number>=max     → full,  pct=100
                channel(4, "VIP频道", 1750, 1800));  // raw=1.16,num<max → vip,   pct=97
        var svc = svc(new FakeScout(raw));

        var views = svc.channels("6");

        assertEquals(4, views.size());
        assertEquals("free", views.get(0).state());
        assertEquals(15, views.get(0).percent());
        assertEquals(180, views.get(0).number());
        assertEquals(1200, views.get(0).maxClient());
        assertEquals("crowd", views.get(1).state());
        assertEquals(70, views.get(1).percent());
        assertEquals("full", views.get(2).state());
        assertEquals(100, views.get(2).percent());
        assertEquals("vip", views.get(3).state());
    }

    @Test
    void channels_cacheHitWithinTtl_callsSourceOnce() {
        var scout = new FakeScout(List.of(channel(1, "f", 10, 1000)));
        var svc = svc(scout);

        svc.channels("6");
        svc.channels("6"); // TTL 内 → 命中缓存，不再实查询

        assertEquals(1, scout.calls.get());
    }

    @Test
    void channels_concurrentSameDistrict_coalescedToOneCall() throws Exception {
        var scout = new FakeScout(List.of(channel(1, "f", 10, 1000)));
        scout.delayMs = 150; // 拉长实查询，确保并发重叠
        var svc = svc(scout);

        int threads = 16;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    svc.channels("6"); // 同区并发
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.await(2, TimeUnit.SECONDS);
        go.countDown(); // 同时放行
        assertTrue(done.await(5, TimeUnit.SECONDS), "并发查询应在超时内完成");

        // single-flight：16 个并发同区请求，底层实查询只发生 1 次
        assertEquals(1, scout.calls.get());
    }

    @Test
    void channels_differentDistricts_runIndependently() {
        var scout = new FakeScout(List.of(channel(1, "f", 10, 1000)));
        var svc = svc(scout);

        svc.channels("6");
        svc.channels("5"); // 不同区 → 各自一次

        assertEquals(2, scout.calls.get());
    }
}
