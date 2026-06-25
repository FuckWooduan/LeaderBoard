package com.rankharvester.web;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.account.GameAccountStore;
import com.rankharvester.net.GameServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抓榜号（{@code game_account}）后台管理：完整增删改查 + 启停 + 改区 + 改 pauth。
 * 受 {@link AdminAuthFilter} 保护（{@code /api/boards/**} 需 X-Admin-Token）。PG dump 批量导入并存保留。
 *
 * <p>抓榜号 {@code servers} 存<strong>登录号</strong>（如 13/11/9），与侦察号的对外区号不同。
 * 写库后 {@link AccountRegistry#reload()} 热生效。
 */
@RestController
public class GameAccountAdminController {

    private final GameAccountStore store;
    private final AccountRegistry registry;

    public GameAccountAdminController(GameAccountStore store, AccountRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** 区表（侦察号填对外区号 districtId；抓榜号填登录号 loginId）；前端下拉用。 */
    @GetMapping("/api/boards/districts")
    public Object districts() {
        var out = new ArrayList<Map<String, Object>>();
        for (GameServer g : GameServer.values()) {
            out.add(Map.of(
                    "districtId", g.districtId(),
                    "loginId", g.id(),
                    "name", g.serverName()));
        }
        return Map.of("districts", out);
    }

    @GetMapping("/api/boards/account/list")
    public Object list() {
        var rows = new ArrayList<Map<String, Object>>();
        for (GameAccount a : registry.all()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", a.id());
            m.put("account", a.account());
            // 凭据不下发明文：仅返回是否已设置 + 脱敏 pauth 预览，编辑留空=保持不变
            m.put("hasPassword", a.password() != null && !a.password().isBlank());
            m.put("pauthMask", mask(a.pauth()));
            m.put("hasPauth", a.pauth() != null && !a.pauth().isBlank());
            m.put("uid", a.uid() == null ? "" : a.uid());
            m.put("servers", a.servers());            // 登录号
            m.put("enabled", a.enabled());
            m.put("available", a.available());
            rows.add(m);
        }
        return Map.of("accounts", rows);
    }

    @PostMapping("/api/boards/account/save")
    public Object save(@RequestBody Map<String, Object> body) {
        String account = str(body.get("account"));
        if (account.isEmpty()) {
            return Map.of("ok", false, "error", "account 不能为空");
        }
        String serversJson = serversJson(body.get("servers"));
        Long id = optLong(body.get("id"));
        boolean enabled = boolOr(body.get("enabled"), true);
        String password = str(body.get("password"));
        String pauth = str(body.get("pauth"));
        long savedId;
        if (id == null) {
            savedId = store.create(account, password, pauth, str(body.get("uid")), serversJson, enabled);
        } else {
            // 编辑时：password / pauth 留空 = 保持原值（前端不回显明文凭据，避免误清空）
            String idStr = String.valueOf(id);
            GameAccount cur = registry.all().stream()
                    .filter(a -> idStr.equals(String.valueOf(a.id()))).findFirst().orElse(null);
            if (cur != null) {
                if (password.isEmpty()) {
                    password = cur.password() == null ? "" : cur.password();
                }
                if (pauth.isEmpty()) {
                    pauth = cur.pauth() == null ? "" : cur.pauth();
                }
            }
            store.update(id, account, password, pauth, str(body.get("uid")), serversJson, enabled);
            savedId = id;
        }
        registry.reload();
        return Map.of("ok", true, "id", savedId);
    }

    /** pauth 脱敏：仅露前 6 位 + 末 4 位，中间省略。 */
    private static String mask(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= 12) {
            return t.charAt(0) + "…";
        }
        return t.substring(0, 6) + "…" + t.substring(t.length() - 4);
    }

    @PostMapping("/api/boards/account/delete")
    public Object delete(@RequestBody Map<String, Object> body) {
        store.delete(reqLong(body.get("id")));
        registry.reload();
        return Map.of("ok", true);
    }

    @PostMapping("/api/boards/account/enable")
    public Object enable(@RequestBody Map<String, Object> body) {
        store.setEnabled(reqLong(body.get("id")), boolOr(body.get("enabled"), true));
        registry.reload();
        return Map.of("ok", true);
    }

    // ───────────── 入参解析 ─────────────

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static Long optLong(Object v) {
        if (v == null || str(v).isEmpty()) return null;
        return reqLong(v);
    }

    private static long reqLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(str(v));
    }

    private static boolean boolOr(Object v, boolean dft) {
        if (v == null) return dft;
        if (v instanceof Boolean b) return b;
        String s = str(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** servers 数组/逗号串 → "[13, 11, 9]"（与 dump 写入格式一致）。 */
    private static String serversJson(Object v) {
        var ids = new ArrayList<String>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String s = str(o);
                if (!s.isEmpty()) ids.add(s);
            }
        } else if (v != null) {
            for (String s : str(v).split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) ids.add(t);
            }
        }
        return "[" + String.join(", ", ids) + "]";
    }
}
