package com.rankharvester.web;

import com.rankharvester.rank.store.MarqueeStore;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 顶部走马灯广告 API。
 *
 * <ul>
 *   <li>{@code GET  /api/boards/marquee}  后台读当前配置（受 {@code /api/boards/**} 鉴权保护）</li>
 *   <li>{@code POST /api/boards/marquee}  后台保存配置（受保护，JSON body）</li>
 *   <li>{@code GET  /api/public/marquee}  前台读启用中的走马灯（公开，lines 已拆数组）</li>
 * </ul>
 */
@RestController
public class MarqueeController {

    /** 图片（data URL / 链接）最大长度，防止超大 base64 撑爆请求/库。约 4MB。 */
    private static final int MAX_IMAGE_LEN = 4 * 1024 * 1024;
    /** 文案最大长度。 */
    private static final int MAX_LINES_LEN = 8 * 1024;

    private final MarqueeStore store;

    public MarqueeController(MarqueeStore store) {
        this.store = store;
    }

    /** 后台保存请求体。 */
    public record SaveReq(Boolean enabled, String lines, String image) {}

    /** 后台读当前配置（原始多行文本，供编辑）。 */
    @GetMapping("/api/boards/marquee")
    public Map<String, Object> adminGet() {
        var c = store.get();
        return Map.of(
                "enabled", c.enabled(),
                "lines", c.lines(),
                "image", c.image() == null ? "" : c.image(),
                "updatedAt", c.updatedAt());
    }

    /** 后台保存。 */
    @PostMapping("/api/boards/marquee")
    public Map<String, Object> adminSave(@RequestBody SaveReq req) {
        String lines = req.lines() == null ? "" : req.lines();
        String image = req.image() == null ? "" : req.image().trim();
        if (lines.length() > MAX_LINES_LEN) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文案过长");
        }
        if (image.length() > MAX_IMAGE_LEN) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片过大（请压缩到 4MB 内，或改用图片链接）");
        }
        boolean enabled = req.enabled() != null && req.enabled();
        store.save(enabled, lines, image, System.currentTimeMillis());
        return Map.of("enabled", enabled, "lines", lines, "image", image);
    }

    /** 前台读：仅启用时返回内容，lines 拆成非空数组。 */
    @GetMapping("/api/public/marquee")
    public Map<String, Object> publicGet() {
        var c = store.get();
        if (!c.enabled()) {
            return Map.of("enabled", false);
        }
        var items = new ArrayList<String>();
        for (String s : c.lines().split("\\r?\\n")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                items.add(t);
            }
        }
        var out = new java.util.HashMap<String, Object>();
        out.put("enabled", true);
        out.put("lines", items);
        out.put("image", c.image() == null ? "" : c.image());
        return out;
    }
}
