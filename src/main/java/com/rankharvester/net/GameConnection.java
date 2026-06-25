package com.rankharvester.net;

import com.rankharvester.apc.ApcObject;
import java.time.Duration;

/**
 * 一条已登录的游戏连接（封装单个 TCP 会话 + APC 收发 + 回包等待）。
 *
 * <p>关键设计：登录一次后可在同一连接上串行发起多次排行榜请求 ——
 * 这是「用尽量少 TCP 连接抓多个榜/多个分区」的落点。回包按 callback 方法名匹配，
 * 同一连接同一时刻只允许一个进行中的 reduced 分页请求（无 requestId 隔离，串行约束）。
 */
public interface GameConnection extends AutoCloseable {

    /** 该连接绑定的账号 id。 */
    String accountId();

    /** 该连接登录的大区。 */
    String server();

    boolean isActive();

    /**
     * 发送一个 APC 请求并阻塞等待指定回包（虚拟线程友好）。
     *
     * @param requestFunction 请求方法名
     * @param expectCallback  期望回包方法名
     * @param timeout         超时
     * @param params          参数
     * @return 回包 APC 对象
     * @throws RuntimeException 超时 / 连接断开 / 服务器异常
     */
    ApcObject call(String requestFunction, String expectCallback, Duration timeout, Object... params);

    /** 发送一个不等待回包的请求（如埋点）。 */
    void send(String requestFunction, Object... params);

    /**
     * 注册<strong>服务器主动推送</strong>回调监听（按方法名）。频道服进频道后会主动推
     * {@code callBackAddRoom}/{@code callBackChangeRoomData}/{@code callBackDeleteRoom} 等增量，
     * 这些回包<strong>无对应请求</strong>，故需独立监听（与 {@link #call} 的请求-回包匹配并存）。
     * 默认实现忽略（模拟连接按需覆写）。
     */
    default void addPushListener(String callbackFunction, java.util.function.Consumer<ApcObject> listener) {
        // 默认不支持（仅真实/模拟频道服连接需要）
    }

    /**
     * 登录握手时捕获的 token（频道服 {@code enterChannel(token, channelId)} 用）。
     * 仅大厅登录连接有；未捕获到返回 null。默认实现返回 null。
     */
    default String loginToken() {
        return null;
    }

    /** 登录握手时捕获的角色等级（进频道前本地等级预筛用）。未捕获返回 0。默认实现返回 0。 */
    default int loginLevel() {
        return 0;
    }

    @Override
    void close();
}
