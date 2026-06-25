package com.rankharvester.web;

import com.rankharvester.account.ScoutAccountService;
import com.rankharvester.account.ScoutAccountStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 侦察号（频道大厅）后台管理。受 {@link AdminAuthFilter} 保护（{@code /api/boards/**} 需 X-Admin-Token）。
 * 增删改后热生效（{@link ScoutAccountService} 内部 reload），无需重启。
 */
@RestController
public class ScoutAccountController {

    private final ScoutAccountService service;

    public ScoutAccountController(ScoutAccountService service) {
        this.service = service;
    }

    @GetMapping("/api/boards/scout/list")
    public Object list() {
        var rows = new ArrayList<Map<String, Object>>();
        for (ScoutAccountStore.Row r : service.list()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", r.id());
            m.put("account", r.account());
            m.put("password", r.password() == null ? "" : r.password());
            m.put("pauth", r.pauth() == null ? "" : r.pauth());
            m.put("uid", r.uid() == null ? "" : r.uid());
            m.put("districts", r.districts());
            m.put("level", r.level()); // 手填等级（可空）
            m.put("probedLevels", r.probedLevels()); // 探测/懒登抓到的每区真实等级
            m.put("probing", service.isProbing(r.id())); // 是否正在探测中
            m.put("enabled", r.enabled());
            m.put("updatedAt", r.updatedAt());
            rows.add(m);
        }
        return Map.of("scouts", rows);
    }

    /** 新增/编辑一个号 → 保存后自动全区探测，区由探测决定（忽略手填区/等级）。 */
    @PostMapping("/api/boards/scout/save")
    public Object save(@RequestBody Map<String, Object> body) {
        Long id = optLong(body.get("id"));
        String account = str(body.get("account"));
        if (account.isEmpty()) {
            return Map.of("ok", false, "error", "account 不能为空");
        }
        var r = service.save(id, account, str(body.get("password")), str(body.get("pauth")), str(body.get("uid")));
        return Map.of("ok", true, "districts", r.districts(), "levels", r.levels());
    }

    /** 批量导入：粘贴 {items:[{account,pauth,uid,password?}]} 一次建多个号 + 并发探测。 */
    @PostMapping("/api/boards/scout/import")
    public Object importBatch(@RequestBody Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of("ok", false, "error", "items 为空");
        }
        var items = new ArrayList<Map<String, String>>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                var it = new java.util.HashMap<String, String>();
                it.put("account", str(m.get("account")));
                it.put("password", str(m.get("password")));
                it.put("pauth", str(m.get("pauth")));
                it.put("uid", str(m.get("uid")));
                items.add(it);
            }
        }
        int imported = service.importBatch(items);
        return Map.of("ok", true, "imported", imported, "async", true);
    }

    @PostMapping("/api/boards/scout/delete")
    public Object delete(@RequestBody Map<String, Object> body) {
        service.delete(reqLong(body.get("id")));
        return Map.of("ok", true);
    }

    @PostMapping("/api/boards/scout/enable")
    public Object enable(@RequestBody Map<String, Object> body) {
        service.setEnabled(reqLong(body.get("id")), boolOr(body.get("enabled"), true));
        return Map.of("ok", true);
    }

    /** 全区探测：拿该号 pauth 对全部区试登录，返回能登的区 + 每区等级，并写回该号 districts。 */
    @PostMapping("/api/boards/scout/probe")
    public Object probe(@RequestBody Map<String, Object> body) {
        var r = service.probeDistricts(reqLong(body.get("id")));
        return Map.of("ok", true, "districts", r.districts(), "levels", r.levels());
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
}
