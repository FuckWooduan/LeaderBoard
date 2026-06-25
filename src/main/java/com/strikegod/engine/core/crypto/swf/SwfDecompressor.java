package com.strikegod.engine.core.crypto.swf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/**
 * SWF 影片解压缩：FWS 原样返回，CWS 从偏移 8 起 zlib 解压还原为 FWS。
 */
public final class SwfDecompressor {

    private SwfDecompressor() {}

    /**
     * 若输入为 CWS（zlib 压缩），解压为 FWS；若已是 FWS 则直通返回。
     *
     * @param swfOrCws SWF 字节（CWS 或 FWS 签名）
     * @return FWS 格式的完整影片字节
     * @throws IOException 非法签名或解压失败时抛出
     */
    public static byte[] decompress(byte[] swfOrCws) throws IOException {
        if (swfOrCws.length < 8) {
            throw new IOException("太短，不是合法 SWF");
        }
        char c0 = (char) swfOrCws[0];
        char c1 = (char) swfOrCws[1];
        char c2 = (char) swfOrCws[2];
        if (c0 == 'F' && c1 == 'W' && c2 == 'S') {
            return swfOrCws;
        }
        if (c0 == 'C' && c1 == 'W' && c2 == 'S') {
            return cwsZlibToFws(swfOrCws);
        }
        throw new IOException("未知 SWF 签名: " + c0 + c1 + c2);
    }

    /**
     * 校验 FWS 头长度字段与实际数组长度是否一致。
     */
    public static boolean headerLengthMatchesPayload(byte[] fws) {
        if (fws.length < 8) {
            return false;
        }
        int n = readLe32(fws, 4);
        return n == fws.length;
    }

    /**
     * 返回字节数组前 N 个字节的十六进制表示（调试用）。
     */
    public static String headHex(byte[] b, int max) {
        return bytesToHex(Arrays.copyOf(b, Math.min(max, b.length)));
    }

    private static byte[] cwsZlibToFws(byte[] cws) throws IOException {
        byte ver = cws[3];
        int declared = readLe32(cws, 4);

        byte[] inflated;
        try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(cws, 8, cws.length - 8));
                ByteArrayOutputStream bos = new ByteArrayOutputStream(
                        declared > 0 ? Math.min(declared, 32 * 1024 * 1024) : cws.length * 4)) {
            inf.transferTo(bos);
            inflated = bos.toByteArray();
        }

        // 某些 CWS 解压后自带完整 FWS 头
        if (inflated.length >= 3 && inflated[0] == 'F' && inflated[1] == 'W' && inflated[2] == 'S') {
            return inflated;
        }

        // 否则手工拼接 FWS 头
        int fileLen = 8 + inflated.length;
        byte[] out = new byte[fileLen];
        out[0] = 'F';
        out[1] = 'W';
        out[2] = 'S';
        out[3] = ver;
        writeLe32(out, 4, fileLen);
        System.arraycopy(inflated, 0, out, 8, inflated.length);
        return out;
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static String bytesToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte x : a) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
