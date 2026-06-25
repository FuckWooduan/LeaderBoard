package com.rankharvester.net;

import com.rankharvester.apc.ApcCodec;
import com.rankharvester.apc.ApcObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 基于 Netty 的真实游戏连接：单 TCP 会话 + APC 收发 + 回包按方法名匹配 + 心跳保活。
 *
 * <p>同一连接上的请求必须串行（无 requestId 隔离，回包按 callback 方法名匹配）——
 * 抓取编排器在同一账号连接上顺序抓多个榜，正好满足此约束，且把 TCP 连接数压到最小。
 */
public final class NettyGameConnection implements GameConnection {

    private static final Logger log = LoggerFactory.getLogger(NettyGameConnection.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** 握手抓包诊断开关（env RANKHARVESTER_GAME_HANDSHAKE_DEBUG=false 可关）。 */
    private static final boolean HS_DEBUG =
            !"false".equalsIgnoreCase(System.getenv("RANKHARVESTER_GAME_HANDSHAKE_DEBUG"));

    private static String toJson(String fn, boolean isTrace, Object params) {
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put("functionName", fn);
            m.put("isTrace", isTrace);
            m.put("parameters", params);
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"functionName\":\"" + fn + "\",\"_jsonError\":\"" + e.getMessage() + "\"}";
        }
    }

    private final String accountId;
    private final String server;
    private final Channel channel;
    private final int heartbeatSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<ApcObject>> pending = new ConcurrentHashMap<>();
    /** 服务器主动推送监听（按回包方法名）：频道服增量 add/change/delete 用，无对应请求。 */
    private final ConcurrentHashMap<String, java.util.function.Consumer<ApcObject>> pushListeners = new ConcurrentHashMap<>();
    /** 登录握手时从 callBackGetUserDataByLogin 顺手捕获的 token（频道服 enterChannel 用）。仅首个完整回包带 token，故只捕获一次。 */
    private volatile String loginToken;
    /** 登录握手时捕获的角色等级（顶层 lv），用于进频道前本地等级预筛；未捕获为 0。 */
    private volatile int loginLevel;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public NettyGameConnection(String accountId, String server, Channel channel, int heartbeatSeconds) {
        this.accountId = accountId;
        this.server = server;
        this.channel = channel;
        this.heartbeatSeconds = heartbeatSeconds;
    }

    /** 由 inbound handler 调用：完成对应回包 future。 */
    public void onInbound(ApcObject apc) {
        var fn = apc.getFunctionName();
        if (HS_DEBUG) {
            log.info("[HS][{}@{}] RECV {}", accountId, server, toJson(fn, apc.isTrace(), apc.getParameters()));
        }
        if (fn == null || fn.isEmpty()) {
            return;
        }
        // 顺手捕获登录 token + 角色等级（首个 callBackGetUserDataByLogin 才带完整；之后服务器回精简包，故只捕一次）
        if (loginToken == null && "callBackGetUserDataByLogin".equals(fn)) {
            Object first = apc.param(0);
            if (first instanceof java.util.Map<?, ?> m) {
                Object t = m.get("token");
                if (t != null && !t.toString().isBlank()) {
                    loginToken = t.toString();
                }
                Object lv = m.get("lv"); // 顶层用户对象的 lv = 角色等级（真机已核：94 级=lv:94）
                if (lv instanceof Number n) {
                    loginLevel = n.intValue();
                }
            }
        }
        var future = pending.remove(fn);
        if (future != null) {
            future.complete(apc);
            return;
        }
        var listener = pushListeners.get(fn);
        if (listener != null) {
            try {
                listener.accept(apc);
            } catch (RuntimeException e) {
                log.warn("[{}] push 监听器处理 {} 异常: {}", accountId, fn, e.toString());
            }
        } else if (log.isTraceEnabled()) {
            log.trace("[{}] 未匹配回包(无等待者): {}", accountId, fn);
        }
    }

    @Override
    public void addPushListener(String callbackFunction, java.util.function.Consumer<ApcObject> listener) {
        pushListeners.put(callbackFunction, listener);
    }

    @Override
    public String loginToken() {
        return loginToken;
    }

    @Override
    public int loginLevel() {
        return loginLevel;
    }

    /** 由 inbound handler 调用：连接异常 / 断开时使所有等待者失败。 */
    public void failAll(Throwable cause) {
        pending.forEach((name, f) -> f.completeExceptionally(cause));
        pending.clear();
    }

    /** 启动心跳定时任务（在 channel 的 eventLoop 上）。 */
    public void startHeartbeat() {
        var heartbeat = ApcCodec.buildPacketBytes("heartBeat", List.of(), true, false); // 大厅 socket 默认不压缩
        heartbeatFuture = channel.eventLoop()
                .scheduleAtFixedRate(
                        () -> {
                            if (channel.isActive()) {
                                channel.writeAndFlush(Unpooled.wrappedBuffer(heartbeat));
                                if (log.isTraceEnabled()) log.trace("[{}] 心跳已发送", accountId);
                            }
                        },
                        heartbeatSeconds,
                        heartbeatSeconds,
                        TimeUnit.SECONDS);
    }

    @Override
    public String accountId() {
        return accountId;
    }

    @Override
    public String server() {
        return server;
    }

    @Override
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    @Override
    public ApcObject call(String requestFunction, String expectCallback, Duration timeout, Object... params) {
        if (!isActive()) {
            throw new IllegalStateException("TCP 连接已断开: " + accountId + "@" + server);
        }
        var future = new CompletableFuture<ApcObject>();
        pending.put(expectCallback, future);
        if (HS_DEBUG) {
            log.info("[HS][{}@{}] SEND {} (expect {})",
                    accountId, server, toJson(requestFunction, true, java.util.Arrays.asList(params)), expectCallback);
        }
        try {
            channel.writeAndFlush(Unpooled.wrappedBuffer(
                    ApcCodec.buildPacketBytes(requestFunction, Arrays.asList(params), true, false)));
        } catch (Exception e) {
            pending.remove(expectCallback, future);
            throw new IllegalStateException("发送失败: " + requestFunction, e);
        }
        try {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (Exception e) {
            pending.remove(expectCallback, future);
            throw new IllegalStateException("等待回包失败/超时: " + expectCallback, e);
        }
    }

    @Override
    public void send(String requestFunction, Object... params) {
        if (!isActive()) {
            throw new IllegalStateException("TCP 连接已断开");
        }
        channel.writeAndFlush(Unpooled.wrappedBuffer(
                ApcCodec.buildPacketBytes(requestFunction, Arrays.asList(params), true, false)));
    }

    @Override
    public void close() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        failAll(new IllegalStateException("连接关闭"));
        // 无条件 close()：失败/重置/超时的连接 isActive()=false，但其 Netty channel（socket FD、
        // pipeline 里帧解码器最大 64MB 的累积缓冲、直接内存）仍注册在 eventLoop 上，只有 close() 才会
        // 注销并释放。旧版用 isActive() 守卫导致大量失败连接的堆外内存永不回收（抓取风暴下 ~30MB/s 泄漏
        // → 每 10 分钟 cgroup OOM）。对已关闭的 channel 再 close() 是安全 no-op。
        if (channel != null) {
            channel.close();
        }
        log.debug("[{}] 连接已关闭 ({})", accountId, server);
    }
}
