package com.rankharvester.account;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 侦察号<strong>等级缓存</strong>（持久化），按 <strong>(账号, 对外区号)</strong> 维度存：{@code accountId@district → 该区角色等级}。
 *
 * <p>方案 B「懒登 + 缓存等级」的存储：号在某区的等级在<strong>第一次需要时登该区一次问出来</strong>就写进这里，
 * 之后纯查缓存、重启不丢（落 {@code data/scout-levels.json}）。
 *
 * <p><b>为何按区</b>：同一 4399 账号在不同区是不同角色、可能不同等级（登录某区抓的 {@code lv} 是该区角色等级），
 * 故等级必须按 (号,区) 区分，否则多区号的等级会互相覆盖、选号判断出错。
 *
 * <p>进频道被拒（疑似等级不符）时由调度器调 {@link #forget} 清该 (号,区) 缓存 → 下次懒登重抓，实现等级自愈。
 */
@Component
public class ScoutLevelStore {

    private static final Logger log = LoggerFactory.getLogger(ScoutLevelStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path file;
    /** key = accountId + "@" + district（对外区号）。 */
    private final Map<String, Integer> levels = new ConcurrentHashMap<>();

    public ScoutLevelStore(@Value("${rankharvester.scout.levels-path:data/scout-levels.json}") String path) {
        this.file = Path.of(path);
    }

    private static String key(String accountId, String district) {
        return accountId + "@" + district;
    }

    @PostConstruct
    void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(Files.readAllBytes(file), Map.class);
            int skipped = 0;
            for (var e : m.entrySet()) {
                // 旧格式（无 "@"，按账号存单一等级）不再兼容——号池小，丢弃后按 (号,区) 重探即可。
                if (!e.getKey().contains("@")) {
                    skipped++;
                    continue;
                }
                if (e.getValue() instanceof Number n) {
                    levels.put(e.getKey(), n.intValue());
                }
            }
            log.info("已载入 {} 条侦察号(号,区)等级缓存（{}{}）", levels.size(), file.toAbsolutePath(),
                    skipped > 0 ? "，丢弃 " + skipped + " 条旧格式" : "");
        } catch (Exception e) {
            log.warn("载入侦察号等级缓存失败 {}: {}", file.toAbsolutePath(), e.toString());
        }
    }

    /** 取某号某区等级；未知返回 0。 */
    public int get(String accountId, String district) {
        return levels.getOrDefault(key(accountId, district), 0);
    }

    /** 是否已知某号某区等级。 */
    public boolean known(String accountId, String district) {
        return levels.containsKey(key(accountId, district));
    }

    /** 记录某号某区等级并持久化。等级 0 合法（记录之，避免被当未知反复懒登）；负数忽略。 */
    public synchronized void put(String accountId, String district, int level) {
        if (level < 0) {
            return;
        }
        Integer prev = levels.put(key(accountId, district), level);
        if (prev == null || prev != level) {
            save();
        }
    }

    /** 忘记某号某区等级（进频道被拒→自愈：清缓存，下次懒登重抓）。 */
    public synchronized void forget(String accountId, String district) {
        if (levels.remove(key(accountId, district)) != null) {
            save();
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, MAPPER.writeValueAsBytes(levels));
        } catch (Exception e) {
            log.warn("持久化侦察号等级缓存失败 {}: {}", file.toAbsolutePath(), e.toString());
        }
    }
}
