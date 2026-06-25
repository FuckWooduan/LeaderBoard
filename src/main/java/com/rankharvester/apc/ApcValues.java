package com.rankharvester.apc;

import java.util.List;
import java.util.Map;

/**
 * AMF3 回包的<strong>弱类型取值</strong>工具——全项目<strong>唯一</strong>的 APC 字段类型转换入口。
 *
 * <p>服务器回包里同一字段可能是 {@code Integer/Long/Double/String} 混着来。本类提供三组方法，
 * 取值规则在此处<strong>唯一定义</strong>（改解析行为只改这一处）：
 *
 * <ul>
 *   <li><b>可空版</b> {@link #asLong(Object)}/{@link #asInt(Object)}/{@link #asBool(Object)}/{@link #asStr(Object)}：
 *       缺失或类型不符返回 {@code null}（装箱），用于需要区分「字段缺失」与「值为 0」的解析场景。</li>
 *   <li><b>默认值版</b> {@link #asLongOr(Object, long)} 等：缺失时回退给定默认（返回原始类型，不会 NPE）。</li>
 *   <li><b>Map 便捷版</b> {@link #asLong(Map, String)} 等：从 map 取键后按「缺失→0/""/false」转换（返回原始类型）。
 *       与可空版按参数个数（arity）区分，互不冲突。</li>
 * </ul>
 */
public final class ApcValues {

    private ApcValues() {}

    // ── 容器 ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    public static List<?> asList(Object o) {
        return (o instanceof List<?> l) ? l : null;
    }

    /** 从可能为 null 的 map 中取键。 */
    public static Object get(Map<String, Object> m, String key) {
        return m == null ? null : m.get(key);
    }

    // ── 可空版（boxed）：缺失/类型不符→null ─────────────────────────

    /** Number→long；数字字符串（含 "306900.0" 这类小数串）→long；否则 null。 */
    public static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                try {
                    return (long) Double.parseDouble(s.trim());
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Integer asInt(Object v) {
        Long l = asLong(v);
        return l == null ? null : l.intValue();
    }

    /** Boolean 原样；Number 非零为真；字符串 "true"/"t"/"1" 为真；否则 null。 */
    public static Boolean asBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s) || "t".equalsIgnoreCase(s) || "1".equals(s);
        }
        return null;
    }

    /** null→null；其余→toString()。 */
    public static String asStr(Object v) {
        return v == null ? null : v.toString();
    }

    // ── 默认值版（primitive）：缺失→dft，不会 NPE ───────────────────

    public static long asLongOr(Object v, long dft) {
        Long l = asLong(v);
        return l == null ? dft : l;
    }

    public static int asIntOr(Object v, int dft) {
        Integer i = asInt(v);
        return i == null ? dft : i;
    }

    public static boolean asBoolOr(Object v, boolean dft) {
        Boolean b = asBool(v);
        return b == null ? dft : b;
    }

    public static String asStrOr(Object v, String dft) {
        return v == null ? dft : v.toString();
    }

    // ── Map 便捷版（primitive，缺失→0/""/false）：与可空版按 arity 区分 ──

    public static int asInt(Map<String, Object> m, String key) {
        return asIntOr(get(m, key), 0);
    }

    public static long asLong(Map<String, Object> m, String key) {
        return asLongOr(get(m, key), 0L);
    }

    public static boolean asBool(Map<String, Object> m, String key) {
        return asBoolOr(get(m, key), false);
    }

    public static String asStr(Map<String, Object> m, String key) {
        String s = asStr(get(m, key));
        return s == null ? "" : s;
    }
}
