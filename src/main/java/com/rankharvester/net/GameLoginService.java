package com.rankharvester.net;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.account.GameAccountStore;
import com.rankharvester.account.ServerRemovalStore;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 登录失败转移：对某大区依次尝试覆盖该区的账号，最多 N 个，拿到第一个成功的连接。
 *
 * <p>loginV3 rc=4（该区封禁）时，把这个大区从该账号剔除（DB + 内存）并换下一个账号。
 */
@Service
public class GameLoginService {

    private static final Logger log = LoggerFactory.getLogger(GameLoginService.class);

    /** 每个大区常驻空闲连接上限：借用复用、用完归还，避免每次查询都重新登录（登录越少越不易被 rc=4 封号）。 */
    private static final int POOL_MAX_PER_SERVER = 3;

    private final GameClient gameClient;
    private final AccountRegistry accounts;
    private final GameAccountStore store;
    private final ServerRemovalStore removalStore;
    private final PandaProxyService pandaProxy;
    private final int maxAccounts;
    /** 每个大区的空闲长连接池（借出即移除→独占使用，归还放回；靠各连接自身心跳保活）。 */
    private final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<GameConnection>> idlePool =
            new ConcurrentHashMap<>();
    /** 每个大区上次成功登录的账号 id（优先复用，避免每次都先试坏 pauth 账号）。 */
    private final ConcurrentHashMap<String, String> lastGoodAccount = new ConcurrentHashMap<>();
    /** 账号最近一次登录失败的时间戳（毫秒），用于把坏账号排到候选末尾。 */
    private final ConcurrentHashMap<String, Long> recentFailAt = new ConcurrentHashMap<>();

    public GameLoginService(
            GameClient gameClient,
            AccountRegistry accounts,
            GameAccountStore store,
            ServerRemovalStore removalStore,
            PandaProxyService pandaProxy,
            @Value("${rankharvester.game.login-failover-max:5}") int maxAccounts) {
        this.gameClient = gameClient;
        this.accounts = accounts;
        this.store = store;
        this.removalStore = removalStore;
        this.pandaProxy = pandaProxy;
        this.maxAccounts = Math.max(1, maxAccounts);
    }

    /**
     * 登录指定大区（失败转移）。
     *
     * @throws IllegalStateException 无可用账号或全部尝试失败
     */
    public GameConnection login(String server) {
        try {
            return login(server, null); // 默认出口（sing-box 固定 trojan）：玩家查询常热连接优先走这条
        } catch (RuntimeException e) {
            // sing-box 固定出口整体不可用（如 trojan 节点全 connection refused / 过期）→ 回退到抓榜同款熊猫住宅 IP，
            // 借其「同一 IP 连试 N 号失败即自动轮换 IP」的自愈能力，避免单一固定出口死亡导致玩家查询 100% 超时。
            ProxyEndpoint panda = pandaAcquire();
            if (panda == null) {
                throw e; // 熊猫未启用/拉取失败：无兜底，照抛原异常
            }
            log.warn("大区 {} 默认出口(sing-box)登录失败（{}），回退熊猫住宅 IP 重试", server, e.getMessage());
            return login(server, panda);
        }
    }

    /**
     * 登录指定大区（失败转移），用指定出口代理。
     * {@code forcedProxy} 为抓榜的熊猫动态 IP；为 null 时走默认 sing-box。
     */
    private GameConnection login(String server, ProxyEndpoint forcedProxy) {
        String good = lastGoodAccount.get(server);
        var candidates = accounts.all().stream()
                .filter(GameAccount::enabled)
                .filter(a -> a.servers() != null && a.servers().contains(server))
                // 上次成功账号优先；其次按「最近失败时间」升序（从未失败的在前，坏 pauth 账号沉底）。
                .sorted(Comparator
                        .comparingInt((GameAccount a) -> a.id().equals(good) ? 0 : 1)
                        .thenComparingLong(a -> recentFailAt.getOrDefault(a.id(), 0L)))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("无覆盖大区 " + server + " 的可用账号");
        }

        // 抓榜(熊猫动态 IP)：同一 IP 连试 strikesPerIp 个号都登录失败 → 弃用该 IP 换新的；最多换 maxIps 个 IP（有限重试，
        // 没成功交上层退避重抓，最终「完成为止」）。玩家查询(forcedProxy=null，sing-box 固定出口)：不换 IP，按 maxAccounts 换号。
        boolean viaPanda = forcedProxy != null && pandaProxy != null && pandaProxy.enabled();
        final int strikesPerIp = viaPanda ? 3 : maxAccounts;
        final int maxIps = viaPanda ? 4 : 1;

        ProxyEndpoint proxy = forcedProxy;
        int tried = 0, ipFails = 0, ipsUsed = 1;
        RuntimeException last = null;
        for (GameAccount acct : candidates) {
            tried++;
            try {
                GameConnection conn = gameClient.connectAndLogin(acct, server, proxy);
                lastGoodAccount.put(server, acct.id());
                recentFailAt.remove(acct.id());
                if (tried > 1) {
                    log.info("大区 {} 第 {} 个账号 {} 登录成功（用了 {} 个 IP）", server, tried, acct.id(), ipsUsed);
                }
                return conn;
            } catch (BannedOnServerException e) {
                log.warn("账号 {} 在大区 {} 封禁(rc=4)，剔除该区并换号", acct.id(), server);
                removeServerQuietly(acct.id(), server);
                recentFailAt.put(acct.id(), System.currentTimeMillis());
                last = e;
                ipFails++;
            } catch (RuntimeException e) {
                log.warn("账号 {} 登录大区 {} 失败，换号: {}", acct.id(), server, e.getMessage());
                recentFailAt.put(acct.id(), System.currentTimeMillis());
                last = e;
                ipFails++;
            }
            if (viaPanda) {
                if (ipFails >= strikesPerIp) {
                    if (ipsUsed >= maxIps) {
                        break; // 换满 maxIps 个 IP 仍不行 → 交上层退避重试
                    }
                    ProxyEndpoint fresh = pandaProxy.acquireDifferent(proxy);
                    if (fresh == null) {
                        break; // 池里没有别的新鲜 IP
                    }
                    log.info("大区 {} 同一 IP 连续 {} 个号登录失败，换新 IP（第 {} 个）", server, strikesPerIp, ipsUsed + 1);
                    proxy = fresh;
                    ipsUsed++;
                    ipFails = 0;
                }
            } else if (tried >= maxAccounts) {
                break; // 玩家查询：保持原来的 maxAccounts 上限
            }
        }
        throw new IllegalStateException(
                "大区 " + server + " 试了 " + tried + " 个号 / " + ipsUsed + " 个 IP 仍无法登录", last);
    }

    /**
     * 借一条该大区的常驻连接：池里有存活的就复用，没有才现登（失败转移）。用完务必 {@link #release} 归还。
     * <p>借出即从池移除→独占使用，避免同一连接被并发调用（连接层无 requestId 隔离，并发会串包）。
     */
    public GameConnection borrow(String server) {
        var dq = idlePool.computeIfAbsent(server, s -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        GameConnection c;
        while ((c = dq.pollFirst()) != null) {
            if (c.isActive()) {
                return c; // 复用存活连接，零登录
            }
            closeQuietly(c); // 已断开，丢弃
        }
        return login(server, pandaAcquire()); // 池空，现登一条（抓榜走熊猫动态 IP）
    }

    /** 抓榜出口：熊猫动态住宅 IP；未启用/拉取失败返回 null（NettyGameClient 回退 sing-box）。 */
    private ProxyEndpoint pandaAcquire() {
        return (pandaProxy != null && pandaProxy.enabled()) ? pandaProxy.acquire() : null;
    }

    /** 归还连接：存活且池未满则放回复用，否则关闭。 */
    public void release(String server, GameConnection conn) {
        if (conn == null) {
            return;
        }
        var dq = idlePool.computeIfAbsent(server, s -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (conn.isActive() && dq.size() < POOL_MAX_PER_SERVER) {
            dq.offerFirst(conn);
        } else {
            closeQuietly(conn);
        }
    }

    /** 借一条「任意」存活连接（跨服全局榜:333/332/天梯 任何区的连接都能拉）。无可用则在任一覆盖区现登。 */
    public GameConnection borrowAny() {
        for (var e : idlePool.entrySet()) {
            GameConnection c;
            while ((c = e.getValue().pollFirst()) != null) {
                if (c.isActive()) {
                    return c;
                }
                closeQuietly(c);
            }
        }
        // 池里没有存活连接：挑一个有覆盖账号的大区现登
        var server = accounts.all().stream()
                .filter(GameAccount::enabled)
                .filter(a -> a.servers() != null && !a.servers().isEmpty())
                .map(a -> a.servers().get(0)).findFirst()
                .orElseThrow(() -> new IllegalStateException("无任何可用账号建立长连接"));
        return login(server, pandaAcquire()); // 跨服全局榜抓取：熊猫动态 IP
    }

    /** 归还 borrowAny 取得的连接（按其 server 放回池）。 */
    public void releaseAny(GameConnection conn) {
        if (conn != null) {
            release(conn.server(), conn);
        }
    }

    private static void closeQuietly(GameConnection conn) {
        try {
            conn.close();
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    private void removeServerQuietly(String accountId, String server) {
        try {
            long idLong = Long.parseLong(accountId.trim());
            store.removeServer(idLong, server);
            removalStore.record(idLong, server, System.currentTimeMillis()); // 记下封号移除，供 7 天复检自动恢复
        } catch (NumberFormatException ignore) {
            // 演示账号 id 非数字，跳过 DB
        } catch (RuntimeException e) {
            log.warn("DB 剔除大区失败 {}@{}: {}", accountId, server, e.getMessage());
        }
        accounts.removeServer(accountId, server);
    }

    /**
     * 复检某账号在某区能否登录（封号解除检测，走熊猫住宅 IP）。供 {@code ServerRecheckService} 7 天周期调用。
     *
     * @return {@code true}=登录成功（封号已解除，可把该区加回账号）；{@code false}=仍 rc=4 封号。
     * @throws RuntimeException 其它瞬时失败（代理/超时等），由调用方按「留待下次」处理。
     */
    public boolean probeLogin(GameAccount acct, String server) {
        GameConnection conn;
        try {
            conn = gameClient.connectAndLogin(acct, server, pandaAcquire());
        } catch (BannedOnServerException e) {
            return false; // 仍封号
        }
        closeQuietly(conn);
        return true; // 已解封
    }
}
