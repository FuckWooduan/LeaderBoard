package com.rankharvester.account;

import java.util.ArrayList;
import java.util.List;

/**
 * StrikeGod PostgreSQL {@code game_account} 导出（pg_dump COPY 块）解析器。
 *
 * <p>纯函数、无 IO、无 Spring 依赖，便于单测。识别形如：
 * <pre>
 * COPY public.game_account (id, created_at, updated_at, account, available, checked_at,
 *      enabled, last_used_at, password, pauth, servers, uid, next_pauth_check_at) FROM stdin;
 * &lt;tab 分隔数据行&gt;
 * \.
 * </pre>
 * 的内容，按表头列名映射，{@code \N} 视为 null，{@code t/f} 视为布尔。
 */
public final class PgGameAccountDump {

    private PgGameAccountDump() {}

    /** 单条种子账号（贴合 game_account 列）。 */
    public record SeedRow(
            long id,
            String account,
            String password,
            String pauth,
            String uid,
            String serversJson,
            boolean available,
            boolean enabled,
            String createdAt,
            String updatedAt,
            String checkedAt,
            String lastUsedAt,
            String nextPauthCheckAt) {}

    /** 解析整个 dump 文本，返回 game_account 的全部数据行。找不到 COPY 块返回空列表。 */
    public static List<SeedRow> parse(String dumpText) {
        var rows = new ArrayList<SeedRow>();
        if (dumpText == null || dumpText.isEmpty()) {
            return rows;
        }
        String[] lines = dumpText.split("\n", -1);
        int i = 0;
        // 定位 COPY 头并解析列顺序
        List<String> columns = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("COPY ") && line.contains("game_account") && line.contains("FROM stdin")) {
                columns = parseColumns(line);
                i++;
                break;
            }
        }
        if (columns == null) {
            return rows;
        }
        // 读取数据行直到终止符 "\."
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.equals("\\.") || line.equals("\\.\r")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length < columns.size()) {
                continue; // 不完整行，跳过
            }
            rows.add(toRow(columns, fields));
        }
        return rows;
    }

    private static List<String> parseColumns(String copyLine) {
        int lp = copyLine.indexOf('(');
        int rp = copyLine.indexOf(')', lp);
        var cols = new ArrayList<String>();
        if (lp < 0 || rp < 0) {
            return cols;
        }
        for (String c : copyLine.substring(lp + 1, rp).split(",")) {
            cols.add(c.trim());
        }
        return cols;
    }

    private static SeedRow toRow(List<String> columns, String[] fields) {
        long id = parseLong(get(columns, fields, "id"), 0L);
        String account = nullable(get(columns, fields, "account"));
        String password = nullable(get(columns, fields, "password"));
        String pauth = nullable(get(columns, fields, "pauth"));
        String uid = nullable(get(columns, fields, "uid"));
        String servers = nullable(get(columns, fields, "servers"));
        boolean available = parseBool(get(columns, fields, "available"));
        boolean enabled = parseBool(get(columns, fields, "enabled"));
        return new SeedRow(
                id, account, password, pauth, uid, servers, available, enabled,
                nullable(get(columns, fields, "created_at")),
                nullable(get(columns, fields, "updated_at")),
                nullable(get(columns, fields, "checked_at")),
                nullable(get(columns, fields, "last_used_at")),
                nullable(get(columns, fields, "next_pauth_check_at")));
    }

    private static String get(List<String> columns, String[] fields, String name) {
        int idx = columns.indexOf(name);
        return (idx < 0 || idx >= fields.length) ? null : fields[idx];
    }

    /** pg COPY 中 {@code \N} 表示 SQL NULL。 */
    private static String nullable(String v) {
        return (v == null || v.equals("\\N")) ? null : v;
    }

    private static boolean parseBool(String v) {
        return "t".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static long parseLong(String v, long dft) {
        if (v == null || v.equals("\\N")) return dft;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
