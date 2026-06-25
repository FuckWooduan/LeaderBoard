package com.rankharvester.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 一个被看频道的<strong>房间态</strong>：内存房间快照 + 该频道的 SSE 订阅者。
 *
 * <p>与「哪个侦察号在伺候」<strong>解耦</strong>——号可能驻留（吃推送）也可能轮巡（定时来刷），
 * 但房间快照和订阅者始终归本对象。{@link RoomScheduler} 负责安排号去更新它。
 *
 * <p><b>锁与广播分离</b>：快照修改在 {@code synchronized} 内（瞬时内存操作），<strong>广播在锁外</strong>——
 * 即「改快照」与「往 SSE 写(可能因慢客户端阻塞)」不共用监视器，慢客户端不会冻结频道状态。
 */
final class ChannelRoomState {

    private final String district;
    private final int channelId;
    private final Set<SseEmitter> subscribers = new CopyOnWriteArraySet<>();
    /** roomId → 房间原始字段。 */
    private final Map<Double, Map<String, Object>> rooms = new LinkedHashMap<>();
    private volatile long lastViewedAt = System.currentTimeMillis();
    /** 不可服务原因（等级不足/授权失败等）；非 null 时前端应显示该提示。 */
    private volatile String unavailableReason;
    /** 广播回调（由 scheduler 注入：把快照映射成 DTO 推给订阅者）。在锁外调用。 */
    private volatile Consumer<ChannelRoomState> broadcaster;

    ChannelRoomState(String district, int channelId) {
        this.district = district;
        this.channelId = channelId;
    }

    String district() { return district; }
    int channelId() { return channelId; }
    long lastViewedAt() { return lastViewedAt; }
    void touch() { lastViewedAt = System.currentTimeMillis(); }
    boolean hasSubscribers() { return !subscribers.isEmpty(); }
    Set<SseEmitter> subscribers() { return subscribers; }
    void setBroadcaster(Consumer<ChannelRoomState> b) { this.broadcaster = b; }
    String unavailableReason() { return unavailableReason; }

    void addSubscriber(SseEmitter em) {
        subscribers.add(em);
        touch();
    }

    void removeSubscriber(SseEmitter em) {
        subscribers.remove(em);
    }

    /** 当前快照（拷贝，供 Web/广播线程安全读）。 */
    synchronized List<Map<String, Object>> snapshot() {
        return new ArrayList<>(rooms.values());
    }

    /** 轮巡模式：整表重置（一次 roomList 的结果）。锁内只改快照，锁外广播。 */
    void replaceAll(List<Map<String, Object>> list) {
        synchronized (this) {
            unavailableReason = null;
            rooms.clear();
            for (Map<String, Object> r : list) {
                Double id = roomId(r);
                if (id != null) {
                    rooms.put(id, r);
                }
            }
        }
        fireChange();
    }

    /** 驻留模式：add/change 增量。锁内只改快照，锁外广播。 */
    void upsert(Map<String, Object> room) {
        Double id = roomId(room);
        if (id == null) {
            return;
        }
        synchronized (this) {
            rooms.put(id, room);
        }
        fireChange();
    }

    /** 驻留模式：delete 增量。锁内只改快照，锁外广播。 */
    void remove(double roomId) {
        boolean changed;
        synchronized (this) {
            changed = rooms.remove(roomId) != null;
        }
        if (changed) {
            fireChange();
        }
    }

    /** 标记该频道不可服务（等级不足/授权失败）：清空房间 + 记原因，广播让前端显示提示。 */
    void markUnavailable(String reason) {
        synchronized (this) {
            unavailableReason = reason;
            rooms.clear();
        }
        fireChange();
    }

    /** 广播（锁外）：把最新快照推给订阅者。 */
    private void fireChange() {
        var b = broadcaster;
        if (b != null) {
            b.accept(this);
        }
    }

    static Double roomId(Map<String, Object> r) {
        Object v = r.get("roomId");
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
