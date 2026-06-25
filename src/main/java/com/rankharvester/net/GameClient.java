package com.rankharvester.net;

import com.rankharvester.account.GameAccount;

/**
 * 游戏 TCP 客户端：负责连接 + 完整握手 + 心跳维持，产出可用的 {@link GameConnection}。
 *
 * <p>握手链对齐主仓 Engine：
 * {@code ping → getUserCharacterState → (getPatchca 验证码) → loginV3 → getUserDataByLogin}，
 * 每步 15s 超时；登录成功后启动 20s 心跳（heartBeat 空参包）保活。
 */
public interface GameClient {

    /**
     * 连接并登录指定账号到指定大区，返回已就绪的连接。
     *
     * @param account 游戏账号（含登录凭据）
     * @param server  目标大区
     * @return 已完成握手、心跳运行中的连接
     * @throws RuntimeException 连接失败 / 握手失败 / 登录被拒
     */
    GameConnection connectAndLogin(GameAccount account, String server);

    /**
     * 同 {@link #connectAndLogin(GameAccount, String)}，但用指定出口代理（抓榜走熊猫动态 IP）。
     * {@code forcedProxy} 为 null 时回退到默认代理选择（sing-box）。默认实现忽略代理（模拟客户端用）。
     */
    default GameConnection connectAndLogin(GameAccount account, String server, ProxyEndpoint forcedProxy) {
        return connectAndLogin(account, server);
    }

    /**
     * 仅<strong>连接</strong>到任意 {@code host:port} 并就绪 APC 收发 + 心跳，<strong>不做大厅登录握手</strong>。
     * 用于「频道服连接」（Tier 2）：连频道服后由调用方自行 {@code enterChannelServer(token) → roomList}。
     *
     * @param label   连接标识（日志用，如 "scout-9-ch119"）
     * @param host    频道服域名/IP
     * @param port    频道服端口
     * @return 已连接、心跳运行中的连接（未登录）
     * @throws UnsupportedOperationException 模拟客户端不支持（dry-run 用合成数据，不开真 socket）
     */
    default GameConnection connectRaw(String label, String host, int port) {
        throw new UnsupportedOperationException("connectRaw 仅真实 Netty 客户端支持");
    }
}
