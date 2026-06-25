package com.rankharvester.apc;

import java.io.InvalidObjectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMF3 反序列化器 —— 支持 APC 协议所需的全部类型。
 *
 * <p>从主仓 StrikeGod.Engine 的 {@code Amf3Reader} 移植，去除一切第三方依赖（纯 JDK）。
 * 关键特性：
 * <ul>
 *   <li>String / Object / Traits 三重引用表</li>
 *   <li>正确处理 sealed + dynamic 属性（2-sealed APC 格式）</li>
 *   <li>支持类型：null、bool、int、double、string、date、array、object、byte array</li>
 * </ul>
 */
final class Amf3Reader {

    private final byte[] data;
    private int pos;
    private final List<String> stringTable = new ArrayList<>();
    private final List<Object> objectTable = new ArrayList<>();
    private final List<Amf3Traits> traitsTable = new ArrayList<>();

    Amf3Reader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    // ──────────────────── 底层读取 ────────────────────

    private int readByte() {
        return data[pos++] & 0xFF;
    }

    int readU29() {
        int result = 0;
        for (int i = 0; i < 3; i++) {
            int b = readByte();
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return result;
        }
        // 第 4 字节使用全部 8 位
        return (result << 8) | readByte();
    }

    private double readDoubleBE() {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | readByte();
        }
        return Double.longBitsToDouble(bits);
    }

    // ──────────────────── String（带引用表） ────────────────────

    String readStringValue() {
        int header = readU29();
        if ((header & 1) == 0) {
            return stringTable.get(header >> 1);
        }

        int length = header >> 1;
        if (length == 0) return "";

        var str = new String(data, pos, length, StandardCharsets.UTF_8);
        pos += length;
        stringTable.add(str);
        return str;
    }

    // ──────────────────── 值读取（类型分派） ────────────────────

    Object readValue() throws InvalidObjectException {
        int marker = readByte();
        return switch (marker) {
            case 0x00 -> null; // undefined → null
            case 0x01 -> null; // null
            case 0x02 -> Boolean.FALSE;
            case 0x03 -> Boolean.TRUE;
            case 0x04 -> readAmf3Integer();
            case 0x05 -> readDoubleBE();
            case 0x06 -> readStringValue();
            case 0x08 -> readDate();
            case 0x09 -> readArray();
            case 0x0A -> readObject();
            case 0x0C -> readByteArray();
            default ->
                throw new InvalidObjectException(
                        String.format("Unsupported AMF3 type marker: 0x%02X at pos %d", marker, pos - 1));
        };
    }

    // ──────────────────── Integer（29-bit 有符号） ────────────────────

    private int readAmf3Integer() {
        int raw = readU29();
        // 29 位有符号整数：若 bit 28 置位则符号扩展
        return (raw & 0x10000000) != 0 ? raw | 0xE0000000 : raw;
    }

    // ──────────────────── Date ────────────────────

    private Double readDate() {
        int header = readU29();
        if ((header & 1) == 0) {
            return (Double) objectTable.get(header >> 1);
        }
        double ms = readDoubleBE();
        objectTable.add(ms);
        return ms;
    }

    // ──────────────────── Array ────────────────────

    private List<Object> readArray() throws InvalidObjectException {
        int header = readU29();
        if ((header & 1) == 0) {
            @SuppressWarnings("unchecked")
            var ref = (List<Object>) objectTable.get(header >> 1);
            return ref;
        }

        int count = header >> 1;
        var result = new ArrayList<Object>(count);
        objectTable.add(result);

        // associative 部分（key-value 直到空字符串）；APC 不使用，但为兼容保留
        String key;
        while (!(key = readStringValue()).isEmpty()) {
            readValue();
        }

        // dense 部分
        for (int i = 0; i < count; i++) {
            result.add(readValue());
        }
        return result;
    }

    // ──────────────────── Object ────────────────────

    private Map<String, Object> readObject() throws InvalidObjectException {
        int header = readU29();
        if ((header & 1) == 0) {
            @SuppressWarnings("unchecked")
            var ref = (Map<String, Object>) objectTable.get(header >> 1);
            return ref;
        }

        Amf3Traits traits;
        if ((header & 2) == 0) {
            traits = traitsTable.get(header >> 2); // traits reference
        } else {
            if ((header & 4) != 0) {
                throw new InvalidObjectException("Externalizable AMF3 objects not supported");
            }
            boolean isDynamic = (header & 8) != 0;
            int sealedCount = header >> 4;
            String className = readStringValue();
            var sealedNames = new String[sealedCount];
            for (int i = 0; i < sealedCount; i++) {
                sealedNames[i] = readStringValue();
            }
            traits = new Amf3Traits(className, sealedNames, isDynamic);
            traitsTable.add(traits);
        }

        var dict = new LinkedHashMap<String, Object>();
        objectTable.add(dict);

        for (int i = 0; i < traits.sealedNames().length; i++) {
            dict.put(traits.sealedNames()[i], readValue());
        }

        if (traits.isDynamic()) {
            String key;
            while (!(key = readStringValue()).isEmpty()) {
                dict.put(key, readValue());
            }
        }
        return dict;
    }

    // ──────────────────── ByteArray ────────────────────

    private byte[] readByteArray() {
        int header = readU29();
        if ((header & 1) == 0) {
            return (byte[]) objectTable.get(header >> 1);
        }
        int length = header >> 1;
        var result = new byte[length];
        System.arraycopy(data, pos, result, 0, length);
        pos += length;
        objectTable.add(result);
        return result;
    }

    record Amf3Traits(String className, String[] sealedNames, boolean isDynamic) {}
}
