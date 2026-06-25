package com.rankharvester.web;

import com.rankharvester.net.ChannelRoomService;
import com.rankharvester.net.GameServer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「频道大厅」对外接口：频道在线人数 + 房间列表，均<strong>按区</strong>、走独立侦察号连接。
 *
 * <p>实查询的并发协调（同区合并、不同区并行、活跃区保温、房间驻留 worker）全在 {@link ChannelRoomService}，
 * 这里只做区服解析与响应包装；返回结构与 {@code /api/public/player/location} 同风格（{@code available + 数据 + ts}）。
 * 缓存/快照命中、轻量，故<strong>不挂极验</strong>。
 */
@RestController
@RequestMapping("/api/public")
public class ChannelRoomController {

    private final ChannelRoomService channelRoom;

    public ChannelRoomController(ChannelRoomService channelRoom) {
        this.channelRoom = channelRoom;
    }

    /** 「频道大厅」区服列表：全部已知大区 + 该区是否已配侦察号（available）。按服号升序。 */
    @GetMapping("/channel-servers")
    public List<Map<String, Object>> channelServers() {
        return Arrays.stream(GameServer.values())
                .sorted(Comparator.comparingInt(GameServer::districtId))
                .map(g -> {
                    String id = String.valueOf(g.districtId());
                    return Map.<String, Object>of(
                            "id", g.districtId(), "name", g.serverName(),
                            "available", channelRoom.available(id));
                })
                .toList();
    }

    /** 某区频道在线人数/容量。 */
    @GetMapping("/channels")
    public Map<String, Object> channels(@RequestParam String server) {
        try {
            return Map.of("available", true, "server", server,
                    "serverName", GameServer.nameOfDistrict(parseIntSafe(server)),
                    "channels", channelRoom.channels(server),
                    "ts", System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            return Map.of("available", false, "server", server, "message", e.getMessage());
        } catch (RuntimeException e) {
            return Map.of("available", false, "server", server, "message", "频道查询失败：" + e.getMessage());
        }
    }

    /** 某区频道列表<strong>实时推送</strong>（SSE）：后端每 5s 拉一次 getRoomChannel 推过来。失败返非200（浏览器不重连）。 */
    @GetMapping(value = "/channels/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object channelsStream(@RequestParam String server) {
        try {
            return channelRoom.subscribeChannels(server);
        } catch (RuntimeException e) {
            return streamReject(e);
        }
    }

    /**
     * SSE 订阅失败时返回<strong>非 200</strong> JSON（HTTP 409）而非 SSE 流。
     * 关键：EventSource 对非 2xx 响应<strong>不会自动重连</strong>，避免「进不去的频道」陷入无限重连风暴打爆连接。
     */
    private static org.springframework.http.ResponseEntity<Map<String, Object>> streamReject(RuntimeException e) {
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** 某区某频道的房间列表（进频道取，驻留 worker 维护实时快照）。 */
    @GetMapping("/rooms")
    public Map<String, Object> rooms(@RequestParam String server, @RequestParam int channel) {
        try {
            return Map.of("available", true, "server", server, "channel", channel,
                    "rooms", channelRoom.rooms(server, channel),
                    "ts", System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            return Map.of("available", false, "server", server, "channel", channel, "message", e.getMessage());
        } catch (RuntimeException e) {
            return Map.of("available", false, "server", server, "channel", channel,
                    "message", "房间查询失败：" + e.getMessage());
        }
    }

    /**
     * 房间<strong>实时推送</strong>（SSE）：建连即推全量,之后 worker 每收到服务器增量就毫秒级推一帧。
     * 前端用 EventSource 订阅,替代轮询。失败（如等级不足/未配号）→ 返<strong>非200</strong>（浏览器不重连，前端 onerror 显示提示）。
     */
    @GetMapping(value = "/rooms/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object roomsStream(@RequestParam String server, @RequestParam int channel) {
        try {
            return channelRoom.subscribe(server, channel);
        } catch (RuntimeException e) {
            return streamReject(e);
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
