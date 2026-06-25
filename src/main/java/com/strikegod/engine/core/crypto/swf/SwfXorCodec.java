package com.strikegod.engine.core.crypto.swf;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * 与客户端 {@code wdnative.lobby_decoder.swfDecode} 等价的变换（自逆 XOR）。
 *
 * <p>
 * 密钥为 Flash 运行时 .rodata 中一段 6 字节字符串， 源码里以 {@code L__2E_str324} 基址按字节索引读取。
 *
 * <p>
 * 注意：此 XOR 循环模式与 {@link com.strikegod.engine.core.crypto.GameResourceDecryptor}
 * 中的 {@code
 * applyXorCopy} 不同——后者用于 .xml / .bin 等资源， 本类专门用于 SWF 文件的 encode=true 场景。
 */
public final class SwfXorCodec {

    private SwfXorCodec() {}

    /**
     * 对加密 SWF 字节执行自逆 XOR 变换（解密/加密同一调用）。
     *
     * @param encrypted 输入字节
     * @param key6      必须长度 6，对应原生内存 {@code L__2E_str324[0..5]}
     * @return 变换后的字节副本
     */
    public static byte[] transform(byte[] encrypted, byte[] key6) {
        if (key6.length != 6) {
            throw new IllegalArgumentException("XOR 密钥长度必须为 6 字节");
        }
        byte[] buf = Arrays.copyOf(encrypted, encrypted.length);
        applyInPlace(buf, key6);
        return buf;
    }

    /**
     * 在原数组上就地执行 XOR 变换。
     */
    public static void applyInPlace(byte[] buf, byte[] key6) {
        int len = buf.length;
        int loc12 = len + (len >> 31 >>> 22);
        int loc11 = loc12 >>> 10;
        int loc10 = 5;
        int loc9 = 0;
        int loc8 = 0;
        if (len >= 1024) {
            loc9 = 0;
            loc10 = 0;
            loc8 = 0;
            while (loc10 < loc11) {
                int keyIndex = 5 + loc9;
                int off = (loc10 << 10) + 91;
                buf[off] = (byte) (buf[off] ^ key6[keyIndex]);
                loc9 = loc8 + 1;
                loc8 = 0;
                if (loc9 != 6) {
                    loc8 = loc9;
                }
                loc9 = 0 - loc8;
                loc10++;
            }
            loc10 = 5 - loc8;
        }
        int tailRemain = len - (loc12 & -1024);
        // 末尾不足一整块时，只有当 tailRemain > 91 才能保证 off=(loc11<<10)+91 在 buf 内：
        // tailRemain==91 时 off 恰好落在 buf.length，越界。
        // 之前用 >= 导致 len 形如 n*1024+91 的 SWF（实测 403547 等）必触 AIOOBE。
        if (tailRemain > 91) {
            int off = (loc11 << 10) + 91;
            buf[off] = (byte) (buf[off] ^ key6[loc10]);
        }
    }

    /**
     * 将 12 位十六进制字符串解析为 6 字节密钥。
     *
     * @param hex12 例如 {@code "332e31343135"}
     * @return 6 字节数组
     */
    public static byte[] parseKeyHex(String hex12) {
        String h = hex12.replace(" ", "").trim();
        if (h.length() != 12) {
            throw new IllegalArgumentException("密钥应为 12 个十六进制字符（6 字节）");
        }
        return HexFormat.of().parseHex(h);
    }
}
