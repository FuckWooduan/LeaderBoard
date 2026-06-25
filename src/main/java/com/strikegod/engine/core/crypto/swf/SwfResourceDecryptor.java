package com.strikegod.engine.core.crypto.swf;

import java.io.IOException;

/**
 * 活动 SWF 资源解密器：与 C# XmlDecrypt 管线一致——默认密钥 ASCII "3.1415"，再 CWS→FWS。
 *
 * <p>
 * 与 {@link com.strikegod.engine.core.crypto.GameResourceDecryptor} 的区别：
 *
 * <ul>
 * <li>XOR 循环模式不同（本类使用客户端 {@code swfDecode} 的算法）
 * <li>解压方式不同（本类还原 CWS→FWS，而非跳 2 字节 + raw deflate）
 * </ul>
 */
public final class SwfResourceDecryptor {

    /**
     * 与 C# XmlDecrypt.KeyBytes 一致：ASCII "3.1415"
     */
    public static final byte[] DEFAULT_XOR_KEY = {0x33, 0x2E, 0x31, 0x34, 0x31, 0x35};

    public static final String DEFAULT_XOR_KEY_HEX = "332e31343135";

    private SwfResourceDecryptor() {}

    /**
     * 使用指定密钥解密活动 SWF：XOR 变换 + CWS 解压为 FWS。
     *
     * @param rawFromCdn CDN 下载的原始加密 SWF 字节
     * @param key6       6 字节 XOR 密钥
     * @return 解压后的 FWS 格式 SWF 字节
     * @throws IOException 解压失败时抛出
     */
    public static byte[] decrypt(byte[] rawFromCdn, byte[] key6) throws IOException {
        if (isFws(rawFromCdn)) {
            return SwfDecompressor.decompress(rawFromCdn);
        }
        if (isCws(rawFromCdn)) {
            try {
                return SwfDecompressor.decompress(rawFromCdn);
            } catch (IOException plainFailure) {
                try {
                    return SwfDecompressor.decompress(SwfXorCodec.transform(rawFromCdn, key6));
                } catch (IOException encryptedFailure) {
                    encryptedFailure.addSuppressed(plainFailure);
                    throw encryptedFailure;
                }
            }
        }
        return SwfDecompressor.decompress(SwfXorCodec.transform(rawFromCdn, key6));
    }

    /**
     * 使用默认密钥（ASCII "3.1415"）解密活动 SWF。
     *
     * @param rawFromCdn CDN 下载的原始加密 SWF 字节
     * @return 解压后的 FWS 格式 SWF 字节
     * @throws IOException 解压失败时抛出
     */
    public static byte[] decrypt(byte[] rawFromCdn) throws IOException {
        return decrypt(rawFromCdn, DEFAULT_XOR_KEY);
    }

    private static boolean isFws(byte[] data) {
        if (data == null || data.length < 3) {
            return false;
        }
        return data[0] == 'F' && data[1] == 'W' && data[2] == 'S';
    }

    private static boolean isCws(byte[] data) {
        if (data == null || data.length < 3) {
            return false;
        }
        return data[0] == 'C' && data[1] == 'W' && data[2] == 'S';
    }
}
