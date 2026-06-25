package com.rankharvester.apc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * APC（游戏侧）二进制帧与业务对象之间的转换工具。纯 JDK 实现，无第三方依赖。
 *
 * <p>基于 AMF3 规范 + 自定义压缩 + 长度前缀，与 Flash/微端客户端约定一致。
 *
 * <h3>数据包格式</h3>
 * <pre>
 * ┌──────────────────┬──────────┬──────────────────┐
 * │ 4 字节大端长度   │ 1 字节   │  AMF3 载荷       │
 * │ (含标志位+载荷)  │ 压缩标志 │ (可选 zlib 压缩) │
 * └──────────────────┴──────────┴──────────────────┘
 * 长度 = 1（标志位） + 载荷字节数
 * 压缩标志：0x00 = 不压缩，0x01 = zlib 压缩
 * </pre>
 *
 * <h3>APC 对象（AS3 dynamic class → common.net.APC）</h3>
 * <pre>
 * sealed 属性：functionName (String), parameters (Array)
 * dynamic 属性：isTrace (Boolean, 仅在 true 时存在)
 * traits header: (2 &lt;&lt; 4) | 8 | 3 = 0x2B
 * </pre>
 */
public final class ApcCodec {

    private static final String APC_CLASS_NAME = "common.net.APC";
    private static final int HEADER_LENGTH_BYTES = 4;
    private static final byte FLAG_UNCOMPRESSED = 0x00;
    private static final byte FLAG_COMPRESSED = 0x01;
    private static final String FIELD_FUNCTION_NAME = "functionName";
    private static final String FIELD_PARAMETERS = "parameters";
    private static final String FIELD_IS_TRACE = "isTrace";

    private ApcCodec() {}

    // ──────────────────── 便捷构造 ────────────────────

    /**
     * 一行构造一个包（isTrace=true，compress=true）。
     *
     * <pre>{@code ApcCodec.quick("buyRpgShop", 1000)}</pre>
     *
     * @return 小写十六进制字符串
     */
    public static String quick(String functionName, Object... parameters) {
        return buildPacketHex(functionName, Arrays.asList(parameters), true, true);
    }

    /** 与 {@link #quick} 等价，直接返回字节数组，便于写入 Channel。 */
    public static byte[] quickBytes(String functionName, Object... parameters) {
        return HexFormat.of().parseHex(quick(functionName, parameters));
    }

    /** 心跳包字节：heartBeat()、空参、isTrace=true，与微端一致。 */
    public static byte[] heartbeatBytes() {
        return buildPacketBytes("heartBeat", List.of(), true, true);
    }

    public static String buildPacketHex(String functionName, List<?> parameters) {
        return buildPacketHex(functionName, parameters, true, true);
    }

    public static String buildPacketHex(String functionName, List<?> parameters, boolean isTrace) {
        return buildPacketHex(functionName, parameters, isTrace, true);
    }

    /** 将函数名 + 参数构造为完整 APC 数据包，返回小写 hex。 */
    public static String buildPacketHex(String functionName, List<?> parameters, boolean isTrace, boolean compress) {
        var payload = serializeApc(functionName, parameters != null ? parameters : List.of(), isTrace);
        var body = compress ? zlibCompress(payload) : payload;

        var packet = new byte[HEADER_LENGTH_BYTES + 1 + body.length];
        var buf = ByteBuffer.wrap(packet);
        buf.putInt(body.length + 1);
        buf.put(compress ? FLAG_COMPRESSED : FLAG_UNCOMPRESSED);
        buf.put(body);
        return HexFormat.of().formatHex(packet);
    }

    public static byte[] buildPacketBytes(String functionName, List<?> parameters) {
        return buildPacketBytes(functionName, parameters, true, true);
    }

    public static byte[] buildPacketBytes(String functionName, List<?> parameters, boolean isTrace, boolean compress) {
        return HexFormat.of().parseHex(buildPacketHex(functionName, parameters, isTrace, compress));
    }

    // ──────────────────── 解析 ────────────────────

    /** 解析 hex 流，返回所有 APC 数据包（支持粘包）。 */
    public static List<ApcPacket> parseHexStream(String hex) {
        try {
            var clean = hex.replace(" ", "").replace("\n", "").replace("\r", "");
            return parseBytes(HexFormat.of().parseHex(clean));
        } catch (Exception ex) {
            return List.of(errorPacket(ex.getMessage()));
        }
    }

    /** 直接从字节流解析所有 APC 数据包（便于 Netty {@code ByteBuf.array()}）。 */
    public static List<ApcPacket> parseByteStream(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        try {
            return parseBytes(bytes);
        } catch (Exception ex) {
            return List.of(errorPacket(ex.getMessage()));
        }
    }

    /** 解析并返回首个数据包；空流或解析失败抛异常。 */
    public static ApcPacket parseFirst(String hex) {
        var packets = parseHexStream(hex);
        if (packets.isEmpty()) {
            throw new IllegalArgumentException("HEX 流中未识别到任何 APC 数据包");
        }
        var first = packets.getFirst();
        if (first.isHasError()) {
            throw new IllegalArgumentException("APC 数据包解析失败: " + first.getError());
        }
        return first;
    }

    /** 解析并返回首个数据包的 {@link ApcObject}。 */
    public static ApcObject parseFirstObj(String hex) {
        var apc = parseFirst(hex).getApcObj();
        if (apc == null) {
            throw new IllegalArgumentException("APC 对象为空，无法解析");
        }
        return apc;
    }

    // ──────────────────── 核心序列化（对齐 AS3 / C# SerializeApc） ────────────────────

    private static byte[] serializeApc(String functionName, List<?> parameters, boolean isTrace) {
        var writer = new Amf3Writer();
        var dynamicProps = new LinkedHashMap<String, Object>();
        if (isTrace) {
            dynamicProps.put(FIELD_IS_TRACE, true);
        }
        writer.writeTypedObject(
                APC_CLASS_NAME,
                new String[] {FIELD_FUNCTION_NAME, FIELD_PARAMETERS},
                new Object[] {functionName, parameters},
                true,
                dynamicProps);
        return writer.toArray();
    }

    // ──────────────────── 多包解析 ────────────────────

    private static List<ApcPacket> parseBytes(byte[] bytes) {
        var result = new ArrayList<ApcPacket>();
        var offset = 0;
        while (offset + 5 <= bytes.length) {
            var sizeField = ((bytes[offset] & 0xFF) << 24)
                    | ((bytes[offset + 1] & 0xFF) << 16)
                    | ((bytes[offset + 2] & 0xFF) << 8)
                    | (bytes[offset + 3] & 0xFF);
            if (sizeField < 1 || offset + 4 + sizeField > bytes.length) break;

            var isCompressed = bytes[offset + 4] != 0;
            var bodyLength = sizeField - 1;
            var bodyStart = offset + 5;

            var packet = new ApcPacket();
            packet.setOffset(offset);
            packet.setTotalSize(4 + sizeField);
            packet.setCompressed(isCompressed);

            try {
                var bodyBytes = Arrays.copyOfRange(bytes, bodyStart, bodyStart + bodyLength);
                var plain = isCompressed ? zlibDecompress(bodyBytes) : bodyBytes;
                var reader = new Amf3Reader(plain);
                packet.setApcObj(deserializeApcObject(reader.readValue()));
            } catch (Exception ex) {
                packet.setHasError(true);
                packet.setError(ex.getMessage());
            }

            result.add(packet);
            offset += 4 + sizeField;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static ApcObject deserializeApcObject(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("AMF3 root is not an object");
        }
        var apc = new ApcObject();
        if (map.containsKey(FIELD_FUNCTION_NAME)) {
            var fn = map.get(FIELD_FUNCTION_NAME);
            apc.setFunctionName(fn instanceof String s ? s : "");
        }
        if (map.containsKey(FIELD_IS_TRACE)) {
            apc.setTrace(Boolean.TRUE.equals(map.get(FIELD_IS_TRACE)));
        }
        if (map.containsKey(FIELD_PARAMETERS) && map.get(FIELD_PARAMETERS) instanceof List<?> list) {
            apc.setParameters(new ArrayList<>((List<Object>) list));
        }
        return apc;
    }

    private static ApcPacket errorPacket(String message) {
        var packet = new ApcPacket();
        packet.setOffset(0);
        packet.setHasError(true);
        packet.setError(message);
        return packet;
    }

    // ──────────────────── Zlib ────────────────────

    private static byte[] zlibCompress(byte[] data) {
        try (var baos = new ByteArrayOutputStream();
                var deflater = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION, false))) {
            deflater.write(data);
            deflater.finish();
            deflater.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Zlib 压缩失败", e);
        }
    }

    private static byte[] zlibDecompress(byte[] data) {
        try (var bais = new ByteArrayInputStream(data);
                var inflater = new InflaterInputStream(bais);
                var baos = new ByteArrayOutputStream()) {
            inflater.transferTo(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Zlib 解压失败", e);
        }
    }
}
