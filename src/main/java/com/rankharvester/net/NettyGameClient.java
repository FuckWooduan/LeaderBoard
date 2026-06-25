package com.rankharvester.net;

import com.rankharvester.account.GameAccount;
import com.rankharvester.apc.ApcObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Netty 的真实游戏 TCP 客户端：连接 + 完整 APC 握手 + 心跳。
 *
 * <p>握手链对齐 Engine：{@code ping → getUserCharacterState → getPatchca(+OCR) → loginV3 → getUserDataByLogin}。
 * 凭据由 {@link CredentialProvider}（flashvars）提供；验证码经 {@link OcrClient} 识别。直连、无代理。
 * 仅在 {@code rankharvester.game.dry-run=false} 时使用。
 */
public final class NettyGameClient implements GameClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NettyGameClient.class);
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private final GameProperties props;
    private final CredentialProvider credentialProvider;
    private final SingboxProxyService proxyService;
    private final EventLoopGroup group;

    public NettyGameClient(GameProperties props, CredentialProvider credentialProvider, SingboxProxyService proxyService) {
        this.props = props;
        this.credentialProvider = credentialProvider;
        this.proxyService = proxyService;
        this.group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @Override
    public GameConnection connectAndLogin(GameAccount account, String server) {
        return connectAndLogin(account, server, null);
    }

    /** 指定代理出口登录（测试节点用）；forcedProxy 为 null 时走 proxyService 轮询。 */
    public GameConnection connectAndLogin(GameAccount account, String server, ProxyEndpoint forcedProxy) {
        var addr = resolveAddress(server);
        var host = addr[0];
        var port = Integer.parseInt(addr[1]);

        var connRef = new AtomicReference<NettyGameConnection>();
        final ProxyEndpoint proxy = forcedProxy != null ? forcedProxy
                : (proxyService != null && proxyService.enabled() ? proxyService.current() : null);
        if (proxy != null) {
            log.info("[{}] 游戏 TCP 走 SOCKS5 代理 {}:{}", account.id(), proxy.host(), proxy.port());
        }
        var bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                // 非池化分配器：连接生命周期短、抓大榜单次回包可达数 MB，pooled 池会缓存大 chunk 不还 OS
                // （堆外只涨不落）。unpooled 用完即释放，配合无条件 close() 根除堆外泄漏。
                .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (proxy != null) {
                            var addr = new InetSocketAddress(proxy.host(), proxy.port());
                            var ph = (proxy.account() != null && !proxy.account().isBlank())
                                    ? new Socks5ProxyHandler(addr, proxy.account(), proxy.password())
                                    : new Socks5ProxyHandler(addr);
                            ch.pipeline().addLast(ph); // 必须最前：先完成 SOCKS 握手
                        }
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 0))
                                .addLast(new ApcInboundDecoder())
                                .addLast(new InboundHandler(connRef));
                    }
                });
        if (proxy != null) {
            // SOCKS 代理需由代理端解析游戏服域名，避免本机 DNS 解析到内网地址
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
        }

        Channel channel;
        try {
            channel = bootstrap.connect(host, port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接被中断: " + host + ":" + port, e);
        } catch (Exception e) {
            // netty 的 promise.sync() 在连接失败时通过 rethrowIfFailed 「sneaky-throw」原始异常，
            // 常为 checked 的 java.net.ConnectException（如 SOCKS5 代理/游戏服不可达「Connection refused」）。
            // 它既非 InterruptedException 也非 RuntimeException，若不在此拦截会穿透上层所有
            // catch(RuntimeException)（登录换号、withConn 重试、playerLookup 兜底）直达 DispatcherServlet → HTTP 500。
            // 统一转成 RuntimeException，让失败转移与兜底逻辑正常生效。
            throw new IllegalStateException("连接失败: " + host + ":" + port + "（" + e.getMessage() + "）", e);
        }

        var conn = new NettyGameConnection(account.id(), server, channel, props.getHeartbeatSeconds());
        connRef.set(conn);

        try {
            handshake(conn, account, server);
            conn.startHeartbeat();
            log.info("[{}] 登录成功 @ {} ({}:{})", account.id(), server, host, port);
            return conn;
        } catch (RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    /** 仅连接到 host:port 并启心跳，不做大厅握手（频道服连接用：连后由调用方 enterChannelServer→roomList）。 */
    @Override
    public GameConnection connectRaw(String label, String host, int port) {
        var connRef = new AtomicReference<NettyGameConnection>();
        var bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                // 非池化分配器：连接生命周期短、抓大榜单次回包可达数 MB，pooled 池会缓存大 chunk 不还 OS
                // （堆外只涨不落）。unpooled 用完即释放，配合无条件 close() 根除堆外泄漏。
                .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 0))
                                .addLast(new ApcInboundDecoder())
                                .addLast(new InboundHandler(connRef));
                    }
                });
        Channel channel;
        try {
            channel = bootstrap.connect(host, port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("频道服连接被中断: " + host + ":" + port, e);
        }
        var conn = new NettyGameConnection(label, host + ":" + port, channel, props.getHeartbeatSeconds());
        connRef.set(conn);
        conn.startHeartbeat();
        log.info("[{}] 频道服已连接 ({}:{})", label, host, port);
        return conn;
    }

    /** 解析大区 host:port：优先内置 GameServer 表（按数字服号），回退 props.servers。 */
    private String[] resolveAddress(String server) {        try {
            var gs = GameServer.fromId(Integer.parseInt(server.trim()));
            if (gs.isPresent()) {
                return new String[] {gs.get().host(), String.valueOf(gs.get().port())};
            }
        } catch (NumberFormatException ignore) {
            // 非数字服号，走 props 配置
        }
        var hostPort = props.getServers().get(server);
        if (hostPort == null || !hostPort.contains(":")) {
            throw new IllegalStateException("未知大区地址: " + server + "（不在 GameServer 表且未配 rankharvester.game.servers）");
        }
        var parts = hostPort.split(":", 2);
        return new String[] {parts[0], parts[1].trim()};
    }

    /** 完整握手序列；任一步失败抛异常。 */
    private void handshake(NettyGameConnection conn, GameAccount account, String server) {
        var timeout = Duration.ofSeconds(props.getCallTimeoutSeconds());

        conn.call("ping", "ping", timeout);

        var cred = credentialProvider.obtain(account, server);

        ApcObject charState = conn.call("getUserCharacterState", "callbackGetUserCharacterState", timeout,
                cred.username(), cred.timestamp(), cred.isAdult(), cred.sign(), cred.extParams());
        // 账号在本区无角色（callbackGetUserCharacterState 返回 false，如刚开放的双线六区）→ 自动注册一个角色，
        // 否则该区无人有角色、getUserDataByLogin 与抓榜查询都拿不到回包。流程移植自 StrikeGod.Engine。
        if (!Boolean.TRUE.equals(charState.param(0))) {
            autoRegisterCharacter(conn, cred, timeout, account, server);
        }

        abortIfCaptcha(conn, cred, timeout);

        ApcObject login = conn.call("loginV3", "callBackLoginV3", timeout,
                cred.username(), cred.isMicroClient(), cred.timestamp(), cred.sign(), cred.isAdult(),
                cred.extParams(), null);
        Object rc = login.param(0);
        int code = (rc instanceof Number n) ? n.intValue() : Integer.MIN_VALUE;
        if (code == 4) {
            throw new BannedOnServerException(account.id(), server);
        }
        if (code != 1) {
            throw new IllegalStateException("loginV3 被拒，返回码: " + rc + "（server=" + server + "）");
        }

        // getUserDataByLogin 取「本账号自己的角色数据」。账号在本区无角色时（如刚开放的双线六区 district 46），
        // 该回包不下发 → 超时。但 loginV3 已 rc=1 鉴权成功，排行榜查询(getReducedRankByPage/getTeamList)
        // 不依赖本人角色，故容忍此超时、登录视为成功继续；否则整服永远抓不到（无人在该区铺号）。
        try {
            conn.call("getUserDataByLogin", "callBackGetUserDataByLogin", timeout);
        } catch (RuntimeException e) {
            log.warn("[{}] getUserDataByLogin 超时/失败（疑本区无角色），登录已鉴权成功，继续 @ {}: {}",
                    account.id(), server, e.getMessage());
        }
    }

    /** 发 getPatchca；若服务端下发验证码图片（ByteArray 非空），按要求直接断开（不接 OCR）。 */
    private void abortIfCaptcha(NettyGameConnection conn, HandshakeCredentials cred, Duration timeout) {
        ApcObject cb = conn.call("getPatchca", "callbackGetPatchca", timeout,
                cred.username(), cred.timestamp(), cred.isAdult(), cred.sign(), cred.extParams());
        byte[] img = firstByteArray(cb, 6);
        if (img == null) {
            img = firstByteArray(cb, 0);
        }
        if (img != null && img.length > 0) {
            throw new IllegalStateException("检测到登录验证码（" + img.length + " 字节），按配置直接断开");
        }
    }

    /**
     * 账号在某区无角色时自动注册（移植自 StrikeGod.Engine）：随机名 {@code checkPlayerNameWithoutLogin} 查重 →
     * {@code createCharacterWithoutLogin} 建角色。建成后该会话的 getUserDataByLogin / 抓榜查询才会有回包。
     * 失败抛出 RuntimeException（上层换号重试）。
     */
    private void autoRegisterCharacter(NettyGameConnection conn, HandshakeCredentials cred, Duration timeout,
            GameAccount account, String server) {
        log.info("[{}] 大区 {} 无角色，自动注册角色", account.id(), server);
        String chosenName = null;
        for (int attempt = 1; attempt <= 5 && chosenName == null; attempt++) {
            String candidate = randomName(8);
            ApcObject check = conn.call("checkPlayerNameWithoutLogin", "callBackCheckPlayerNameWithoutLogin", timeout,
                    cred.username(), cred.timestamp(), cred.isAdult(), cred.sign(), candidate, cred.extParams());
            if (asInt(check.param(0)) == 1) {
                chosenName = candidate;
            }
        }
        if (chosenName == null) {
            throw new IllegalStateException("自动注册：连续随机名均不可用 @ " + server);
        }
        ApcObject create = conn.call("createCharacterWithoutLogin", "callbackCreateCharacterWithoutLogin", timeout,
                cred.username(), cred.timestamp(), cred.isAdult(), cred.sign(), chosenName, cred.extParams());
        if (asInt(create.param(0)) != 1) {
            throw new IllegalStateException("自动注册：创建角色失败 @ " + server);
        }
        log.info("[{}] 自动注册角色成功 @ {}: {}", account.id(), server, chosenName);
    }

    private static int asInt(Object v) {
        return (v instanceof Number n) ? n.intValue() : -1;
    }

    private static final char[] NAME_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static String randomName(int len) {
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(NAME_CHARS[rnd.nextInt(NAME_CHARS.length)]);
        }
        return sb.toString();
    }

    private static byte[] firstByteArray(ApcObject apc, int index) {
        Object v = apc.param(index);
        return (v instanceof byte[] b) ? b : null;
    }

    @Override
    public void close() {
        group.shutdownGracefully();
    }

    private static final class InboundHandler extends SimpleChannelInboundHandler<ApcObject> {
        private final AtomicReference<NettyGameConnection> connRef;

        InboundHandler(AtomicReference<NettyGameConnection> connRef) {
            this.connRef = connRef;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ApcObject msg) {
            var conn = connRef.get();
            if (conn != null) {
                conn.onInbound(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("TCP 入站异常: {}", cause.toString());
            var conn = connRef.get();
            if (conn != null) {
                conn.failAll(cause);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            var conn = connRef.get();
            if (conn != null) {
                conn.failAll(new IllegalStateException("channel inactive"));
            }
        }
    }
}
