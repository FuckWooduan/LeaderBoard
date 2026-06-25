package com.rankharvester.web;

import com.rankharvester.weapon.Weapon;
import com.rankharvester.weapon.WeaponStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 武器查询（前台公开）。
 *
 * <ul>
 *   <li>{@code GET /api/public/weapon/search?q=&limit=} 按武器名/代号模糊搜索（精简）</li>
 *   <li>{@code GET /api/public/weapon/{id}}            武器详情（基础 + 面板）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/public/weapon")
public class WeaponController {

    private final WeaponStore store;

    public WeaponController(WeaponStore store) {
        this.store = store;
    }

    /** 每页条数。 */
    private static final int PAGE = 100;

    /**
     * 统一翻页搜索：武器(含面板) + 辅助条目(武器技能/配件/词缀…)合并为一个列表，武器在前。每页 {@value #PAGE}。
     * 返回 {@code {total, page, size, items:[{type:"weapon"|"entry", ...}]}}。
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page) {
        if (q.isBlank()) {
            return Map.of("total", 0, "page", 1, "size", PAGE, "items", List.of(), "sync", syncMap());
        }
        int p = Math.max(1, page);
        int offset = (p - 1) * PAGE;
        long wTotal = store.countWeapons(q);
        long eTotal = store.countEntries(q);
        var items = new ArrayList<Map<String, Object>>();

        // 武器段（位置 0..wTotal）
        if (offset < wTotal) {
            int wLim = (int) Math.min(PAGE, wTotal - offset);
            for (var w : store.search(q, offset, wLim)) {
                items.add(Map.of("type", "weapon", "id", w.id(),
                        "name", nz(w.name()), "codeName", nz(w.codeName()), "typeLabel", nz(w.typeLabel())));
            }
        }
        // 辅助条目段填满本页
        if (items.size() < PAGE) {
            int eOff = (int) Math.max(0, offset - wTotal);
            for (var e : store.searchEntries(q, eOff, PAGE - items.size())) {
                items.add(Map.of("type", "entry", "catLabel", nz(e.catLabel()),
                        "key", nz(e.key()), "name", nz(e.name()), "detail", nz(e.detail()),
                        "payload", nz(e.payload())));
            }
        }
        return Map.of("total", wTotal + eTotal, "page", p, "size", PAGE, "items", items, "sync", syncMap());
    }

    /** 武器库同步状态（GameData + GameDataOther 解析落库后的本地更新时间）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return syncMap();
    }

    private Map<String, Object> syncMap() {
        var s = store.syncStatus();
        return Map.of(
                "weapons", s.weapons(),
                "weaponUpdatedAt", s.weaponUpdatedAt(),
                "entries", s.entries(),
                "entryUpdatedAt", s.entryUpdatedAt(),
                "updatedAt", s.updatedAt(),
                "schedule", "每日 18:10（北京时间）自动同步；武器库为空时启动后自动同步一次");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @GetMapping("/{id}")
    public Weapon detail(@PathVariable long id) {
        return store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到武器: " + id));
    }
}
