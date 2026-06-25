package com.rankharvester.apc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AMF3 序列化器 —— 支持 APC 协议所需的全部类型。
 *
 * <p>从主仓 StrikeGod.Engine 的 {@code Amf3Writer} 移植，去除一切第三方依赖（纯 JDK）。
 * 关键特性：
 * <ul>
 *   <li>String 引用表去重（重复字符串只写一次）</li>
 *   <li>{@link #writeTypedObject} 支持 sealed + dynamic 属性（匹配 AS3 registerClassAlias 行为）</li>
 *   <li>完整类型映射：null / boolean / int / double / string / byte[] / List / Map</li>
 * </ul>
 */
final class Amf3Writer {

    private final ByteArrayOutputStream ms = new ByteArrayOutputStream();
    private final List<String> stringTable = new ArrayList<>();
    private final Map<String, Integer> stringIndex = new HashMap<>();

    byte[] toArray() {
        return ms.toByteArray();
    }

    // ──────────────────── 底层写入 ────────────────────

    private void writeByte(int b) {
        ms.write(b);
    }

    void writeU29(int value) {
        value &= 0x1FFFFFFF;
        if (value < 0x80) {
            writeByte(value);
        } else if (value < 0x4000) {
            writeByte(value >> 7 | 0x80);
            writeByte(value & 0x7F);
        } else if (value < 0x200000) {
            writeByte(value >> 14 | 0x80);
            writeByte(value >> 7 | 0x80);
            writeByte(value & 0x7F);
        } else {
            writeByte(value >> 22 | 0x80);
            writeByte(value >> 15 | 0x80);
            writeByte(value >> 8 | 0x80);
            writeByte(value & 0xFF);
        }
    }

    private void writeDoubleBE(double value) {
        long bits = Double.doubleToLongBits(value);
        writeByte((int) ((bits >>> 56) & 0xFF));
        writeByte((int) ((bits >>> 48) & 0xFF));
        writeByte((int) ((bits >>> 40) & 0xFF));
        writeByte((int) ((bits >>> 32) & 0xFF));
        writeByte((int) ((bits >>> 24) & 0xFF));
        writeByte((int) ((bits >>> 16) & 0xFF));
        writeByte((int) ((bits >>> 8) & 0xFF));
        writeByte((int) (bits & 0xFF));
    }

    // ──────────────────── String（带引用表） ────────────────────

    void writeStringValue(String s) {
        if (s == null || s.isEmpty()) {
            writeU29(1); // inline, length 0: (0 << 1) | 1
            return;
        }
        var idx = stringIndex.get(s);
        if (idx != null) {
            writeU29(idx << 1); // reference
            return;
        }
        var utf8 = s.getBytes(StandardCharsets.UTF_8);
        writeU29((utf8.length << 1) | 1); // inline
        ms.write(utf8, 0, utf8.length);
        stringIndex.put(s, stringTable.size());
        stringTable.add(s);
    }

    // ──────────────────── 值写入（类型分派） ────────────────────

    void writeValue(Object value) {
        if (value == null) {
            writeByte(0x01); // null
            return;
        }
        switch (value) {
            case Boolean b -> writeByte(b ? 0x03 : 0x02);
            case Integer i -> {
                // AMF3 integer: 29 位有符号范围 [-2^28, 2^28-1]
                if (i >= -268435456 && i <= 268435455) {
                    writeByte(0x04);
                    writeU29(i & 0x1FFFFFFF);
                } else {
                    writeByte(0x05);
                    writeDoubleBE(i);
                }
            }
            case Long l -> {
                writeByte(0x05);
                writeDoubleBE(l);
            }
            case Double d -> {
                writeByte(0x05);
                writeDoubleBE(d);
            }
            case Float f -> {
                writeByte(0x05);
                writeDoubleBE(f);
            }
            case String s -> {
                writeByte(0x06);
                writeStringValue(s);
            }
            case byte[] ba -> writeByteArrayValue(ba);
            case List<?> list -> writeArray(list);
            case Map<?, ?> map -> {
                @SuppressWarnings("unchecked")
                var dict = (Map<String, Object>) map;
                writeAnonymousObject(dict);
            }
            default -> {
                // 回退：toString() 作为字符串
                writeByte(0x06);
                writeStringValue(value.toString());
            }
        }
    }

    // ──────────────────── Array ────────────────────

    private void writeArray(List<?> list) {
        writeByte(0x09);
        writeU29((list.size() << 1) | 1);
        writeStringValue(""); // 空 associative 部分
        for (var item : list) {
            writeValue(item);
        }
    }

    // ──────────────────── ByteArray ────────────────────

    private void writeByteArrayValue(byte[] data) {
        writeByte(0x0C);
        writeU29((data.length << 1) | 1);
        ms.write(data, 0, data.length);
    }

    // ──────────────────── Typed Object（sealed + dynamic） ────────────────────

    /**
     * 写入带类名的 AMF3 typed object（匹配 AS3 dynamic class 结构）。
     *
     * <p>对于 APC 对象：sealedNames = {"functionName","parameters"}，isDynamic = true，
     * dynamicProps 在 isTrace=true 时含 {"isTrace": true}。
     * 生成 traits header: (2 &lt;&lt; 4) | 8 | 3 = 0x2B，与 AS3 / C# 完全一致。
     */
    void writeTypedObject(
            String className,
            String[] sealedNames,
            Object[] sealedValues,
            boolean isDynamic,
            Map<String, Object> dynamicProps) {
        writeByte(0x0A);
        var header = (sealedNames.length << 4) | (isDynamic ? 8 : 0) | 3;
        writeU29(header);

        writeStringValue(className);
        for (var name : sealedNames) {
            writeStringValue(name);
        }
        for (var val : sealedValues) {
            writeValue(val);
        }

        if (isDynamic) {
            if (dynamicProps != null && !dynamicProps.isEmpty()) {
                for (var entry : dynamicProps.entrySet()) {
                    writeStringValue(entry.getKey());
                    writeValue(entry.getValue());
                }
            }
            writeStringValue(""); // 终止 dynamic 部分（即使无属性也必须写）
        }
    }

    // ──────────────────── Anonymous Object（全 dynamic） ────────────────────

    private void writeAnonymousObject(Map<String, ?> dict) {
        writeByte(0x0A);
        writeU29((0 << 4) | 8 | 3); // = 0x0B：0 sealed + dynamic + inline
        writeStringValue(""); // 空类名
        for (var entry : dict.entrySet()) {
            writeStringValue(entry.getKey());
            writeValue(entry.getValue());
        }
        writeStringValue(""); // 终止 dynamic 部分
    }
}
