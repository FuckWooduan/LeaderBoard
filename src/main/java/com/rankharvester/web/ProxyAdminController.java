package com.rankharvester.web;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.net.BannedOnServerException;
import com.rankharvester.net.GameClient;
import com.rankharvester.net.NettyGameClient;
import com.rankharvester.net.ProxyEndpoint;
import com.rankharvester.rank.slice.RankSliceStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代理节点实测（管理）：对每个 sing-box 出口端口，用「分区发现已验证过的有效(账号,区)」登录若干次，
 * 统计 ok / rc4(被限流) / fail，便于只保留有效节点。受 AdminAuthFilter 保护。
 */
@RestController
public class ProxyAdminController {

    private static final Logger log = LoggerFactory.getLogger(ProxyAdminController.class);

    private final GameClient gameClient;
    private final AccountRegistry accounts;
    private final RankSliceStore sliceStore;

    public ProxyAdminController(GameClient gameClient, AccountRegistry accounts, RankSliceStore sliceStore) {
        this.gameClient = gameClient;
        this.accounts = accounts;
        this.sliceStore = sliceStore;
    }

    /**
     * 实测端口 [from, to] 每个端口 tries 次（用不同的有效账号），返回每端口 ok/rc4/fail。
     * rc4 = loginV3 返回 4（IP 访问太多被限流，非账号封禁）。
     */
    @PostMapping("/api/boards/proxy-test")
    public Object test(@RequestParam(defaultValue = "2333") int from,
            @RequestParam(defaultValue = "2355") int to,
            @RequestParam(defaultValue = "3") int tries) {
        if (!(gameClient instanceof NettyGameClient ng)) {
            return Map.of("ok", false, "message", "当前为 dry-run 模拟客户端，无法实测");
        }
        // 已验证过的 (账号,区)：分区发现成功的来源 = 能登录的非 ban 号
        var combos = new ArrayList<String[]>();
        for (var s : sliceStore.allSourceTasks()) {
            combos.add(new String[] {s.accountId(), s.server()});
        }
        if (combos.isEmpty()) {
            return Map.of("ok", false, "message", "暂无已验证账号（分区发现还没成功过）");
        }
        var byId = new HashMap<String, GameAccount>();
        for (var a : accounts.all()) {
            byId.put(a.id(), a);
        }
        int n = Math.max(2, Math.min(to - from + 1, 64));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Map<String, Object>>>();
            for (int p = from; p <= to; p++) {
                final int port = p;
                futures.add(pool.submit((Callable<Map<String, Object>>) () -> probePort(ng, byId, combos, port, tries)));
            }
            var out = new ArrayList<Map<String, Object>>();
            for (var f : futures) {
                try {
                    out.add(f.get());
                } catch (Exception e) {
                    /* 忽略单端口异常 */
                }
            }
            out.sort((a, b) -> Integer.compare((int) a.get("port"), (int) b.get("port")));
            long good = out.stream().filter(m -> (int) m.get("ok") > 0).count();
            return Map.of("ok", true, "ports", out, "goodPorts", good, "totalPorts", out.size());
        }
    }

    private Map<String, Object> probePort(NettyGameClient ng, Map<String, GameAccount> byId,
            List<String[]> combos, int port, int tries) {
        int ok = 0;
        int rc4 = 0;
        int fail = 0;
        for (int t = 0; t < tries; t++) {
            String[] c = combos.get(Math.floorMod(port * 31 + t, combos.size())); // 每次换不同账号
            GameAccount acct = byId.get(c[0]);
            if (acct == null) {
                fail++;
                continue;
            }
            var proxy = new ProxyEndpoint("127.0.0.1", port, null, null);
            try {
                var conn = ng.connectAndLogin(acct, c[1], proxy);
                try { conn.close(); } catch (RuntimeException ignore) { /* best-effort */ }
                ok++;
            } catch (BannedOnServerException e) {
                rc4++; // loginV3 rc=4：该出口 IP 被限流/拒
            } catch (RuntimeException e) {
                fail++;
            }
        }
        var m = new HashMap<String, Object>();
        m.put("port", port);
        m.put("ok", ok);
        m.put("rc4", rc4);
        m.put("fail", fail);
        return m;
    }
}
