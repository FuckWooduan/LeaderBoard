package com.rankharvester.gamedata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 游戏加密资源文件解密器（移植自 StrikeGod.Engine core/crypto）。
 *
 * <p><b>.swf / .xml</b>：每 1024 字节块在块内偏移 91 处用密钥字节 XOR 还原 → 跳过前 2 字节后 raw deflate 解压一层。
 *
 * <p><b>.bin</b>：同上 XOR → 连续两次标准 zlib 解压 → 剩余字节按连续 AMF3 对象解码并序列化为 JSON 数组。
 *
 * <p>密钥为 ASCII "3.1415"。
 */
public final class GameResourceDecryptor {

    private static final byte[] KEY_BYTES = {0x33, 0x2E, 0x31, 0x34, 0x31, 0x35};

    private static final int BLOCK_SIZE = 1024;
    private static final int OFFSET_IN_BLOCK = 91;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GameResourceDecryptor() {}

    /** 解密 .swf、.xml 等：XOR + 跳过 2 字节 + 单层 deflate。失败时 success=false、data=原始。 */
    public static GameResourceDecryptResult decrypt(byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return new GameResourceDecryptResult(false, rawData);
        }
        try {
            byte[] workingData = applyXorCopy(rawData);
            if (workingData.length <= 2) {
                return new GameResourceDecryptResult(false, rawData);
            }
            var inflater = new Inflater(true); // raw deflate（跳过 zlib 头 2 字节）
            inflater.setInput(workingData, 2, workingData.length - 2);
            var outputStream = new ByteArrayOutputStream(workingData.length * 2);
            var buffer = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                outputStream.write(buffer, 0, count);
            }
            inflater.end();
            return new GameResourceDecryptResult(true, outputStream.toByteArray());
        } catch (Exception e) {
            return new GameResourceDecryptResult(false, rawData);
        }
    }

    /** 判断下载内容是否已是明文 XML（res_config.xml 当前为明文）。 */
    public static boolean looksLikePlainXml(byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        int offset = 0;
        if (rawData.length >= 3
                && (rawData[0] & 0xff) == 0xef
                && (rawData[1] & 0xff) == 0xbb
                && (rawData[2] & 0xff) == 0xbf) {
            offset = 3;
        }
        while (offset < rawData.length) {
            int b = rawData[offset] & 0xff;
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                offset++;
                continue;
            }
            return b == '<';
        }
        return false;
    }

    /** 解密 .bin：XOR + 两次 zlib + AMF3 流 → JSON UTF-8（pretty）。失败时 success=false、data=原始。 */
    public static GameResourceDecryptResult decryptBin(byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return new GameResourceDecryptResult(false, rawData);
        }
        try {
            byte[] xored = applyXorCopy(rawData);
            byte[] layer1 = zlibDecompress(xored);
            byte[] layer2 = zlibDecompress(layer1);
            String json = decodeAmf3PayloadToPrettyJson(layer2);
            return new GameResourceDecryptResult(true, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new GameResourceDecryptResult(false, rawData);
        }
    }

    private static byte[] applyXorCopy(byte[] rawData) {
        byte[] workingData = new byte[rawData.length];
        System.arraycopy(rawData, 0, workingData, 0, rawData.length);
        int totalBlocks = (workingData.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
        for (int k = 0; k < totalBlocks; k++) {
            int targetPos = k * BLOCK_SIZE + OFFSET_IN_BLOCK;
            if (targetPos < workingData.length) {
                int keyIndex = 5 - (k % 6);
                workingData[targetPos] ^= KEY_BYTES[keyIndex];
            }
        }
        return workingData;
    }

    private static byte[] zlibDecompress(byte[] input) throws IOException {
        try (var bais = new ByteArrayInputStream(input);
                var iis = new InflaterInputStream(bais, new Inflater())) {
            return readAll(iis);
        }
    }

    private static byte[] readAll(InflaterInputStream iis) throws IOException {
        try (var out = new ByteArrayOutputStream(Math.max(256, iis.available() * 2))) {
            var buf = new byte[8192];
            int n;
            while ((n = iis.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String decodeAmf3PayloadToPrettyJson(byte[] amfPayload) throws IOException {
        var decoder = new Amf3JsonDecoder(amfPayload);
        ArrayNode arrayNode = JSON.createArrayNode();
        while (decoder.hasRemaining()) {
            try {
                JsonNode value = decoder.readValue();
                if (value == null || value.isNull()) {
                    break;
                }
                arrayNode.add(value);
            } catch (EOFException e) {
                break;
            }
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(arrayNode);
        } catch (JacksonException e) {
            throw new IOException("AMF3 结果序列化为 JSON 失败", e);
        }
    }

    private static final class Amf3JsonDecoder {

        private static final int UNDEFINED = 0x00;
        private static final int NULL = 0x01;
        private static final int FALSE = 0x02;
        private static final int TRUE = 0x03;
        private static final int INTEGER = 0x04;
        private static final int DOUBLE = 0x05;
        private static final int STRING = 0x06;
        private static final int XML_DOC = 0x07;
        private static final int DATE = 0x08;
        private static final int ARRAY = 0x09;
        private static final int OBJECT = 0x0A;
        private static final int XML = 0x0B;
        private static final int BYTE_ARRAY = 0x0C;
        private static final int VECTOR_INT = 0x0D;
        private static final int VECTOR_UINT = 0x0E;
        private static final int VECTOR_DOUBLE = 0x0F;
        private static final int VECTOR_OBJECT = 0x10;
        private static final int DICTIONARY = 0x11;

        private final byte[] data;
        private final List<String> stringRefs = new ArrayList<>();
        private final List<JsonNode> objectRefs = new ArrayList<>();
        private final List<TraitInfo> traitRefs = new ArrayList<>();
        private int pos;

        private Amf3JsonDecoder(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        private boolean hasRemaining() {
            return pos < data.length;
        }

        private JsonNode readValue() throws IOException {
            int marker = readU8();
            return switch (marker) {
                case UNDEFINED, NULL -> JSON.nullNode();
                case FALSE -> JSON.getNodeFactory().booleanNode(false);
                case TRUE -> JSON.getNodeFactory().booleanNode(true);
                case INTEGER -> JSON.getNodeFactory().numberNode(readU29Signed());
                case DOUBLE -> JSON.getNodeFactory().numberNode(readDouble());
                case STRING -> JSON.getNodeFactory().textNode(readString());
                case XML_DOC, XML -> readXmlLike(marker == XML_DOC ? "XMLDocument" : "XML");
                case DATE -> readDate();
                case ARRAY -> readArray();
                case OBJECT -> readObject();
                case BYTE_ARRAY -> readByteArray();
                case VECTOR_INT -> readVectorInt();
                case VECTOR_UINT -> readVectorUInt();
                case VECTOR_DOUBLE -> readVectorDouble();
                case VECTOR_OBJECT -> readVectorObject();
                case DICTIONARY -> readDictionary();
                default -> throw new IOException("未知 AMF3 类型标记: 0x" + Integer.toHexString(marker));
            };
        }

        private JsonNode readXmlLike(String type) throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                return refNode(type, header >> 1);
            }
            String text = readUtf8(header >> 1);
            var node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", type);
            node.put("Text", text);
            objectRefs.add(node);
            return node;
        }

        private JsonNode readDate() throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                return objectRef(header >> 1);
            }
            var node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", "Date");
            node.put("Milliseconds", (long) readDouble());
            objectRefs.add(node);
            return node;
        }

        private JsonNode readArray() throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                return objectRef(header >> 1);
            }
            int denseCount = header >> 1;
            var assoc = JSON.createObjectNode();
            while (true) {
                String key = readString();
                if (key.isEmpty()) {
                    break;
                }
                assoc.set(key, readValue());
            }
            if (assoc.isEmpty()) {
                ArrayNode arr = JSON.createArrayNode();
                objectRefs.add(arr);
                for (int i = 0; i < denseCount; i++) {
                    arr.add(readValue());
                }
                return arr;
            }
            ObjectNode node = JSON.createObjectNode();
            objectRefs.add(node);
            node.put("__AMF_TYPE__", "Array");
            node.set("Associative", assoc);
            ArrayNode dense = JSON.createArrayNode();
            for (int i = 0; i < denseCount; i++) {
                dense.add(readValue());
            }
            node.set("Dense", dense);
            return node;
        }

        private JsonNode readObject() throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                return objectRef(header >> 1);
            }
            TraitInfo traits;
            if ((header & 3) == 1) {
                traits = traitRef(header >> 2);
            } else {
                boolean externalizable = (header & 4) != 0;
                boolean dynamic = (header & 8) != 0;
                int sealedCount = header >> 4;
                String className = readString();
                List<String> sealedNames = new ArrayList<>();
                for (int i = 0; i < sealedCount; i++) {
                    sealedNames.add(readString());
                }
                traits = new TraitInfo(className, sealedNames, dynamic, externalizable);
                traitRefs.add(traits);
            }
            ObjectNode node = JSON.createObjectNode();
            objectRefs.add(node);
            if (!traits.className().isEmpty()) {
                node.put("__AMF_CLASS__", traits.className());
            }
            if (traits.externalizable()) {
                node.set("__AMF_EXTERNALIZED__", readValue());
                return node;
            }
            for (String sealedName : traits.sealedNames()) {
                node.set(sealedName, readValue());
            }
            if (traits.dynamic()) {
                while (true) {
                    String key = readString();
                    if (key.isEmpty()) {
                        break;
                    }
                    node.set(key, readValue());
                }
            }
            return node;
        }

        private JsonNode readByteArray() throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                return objectRef(header >> 1);
            }
            int length = header >> 1;
            byte[] bytes = readBytes(length);
            var node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", "ByteArray");
            node.put("Length", bytes.length);
            node.put("Base64", Base64.getEncoder().encodeToString(bytes));
            objectRefs.add(node);
            return node;
        }

        private JsonNode readVectorInt() throws IOException {
            int count = readInlineObjectCount("Vector<int>");
            boolean fixed = readU8() != 0;
            ArrayNode arr = JSON.createArrayNode();
            objectRefs.add(arr);
            for (int i = 0; i < count; i++) {
                arr.add(readInt32());
            }
            return vectorNode("Vector<int>", fixed, arr);
        }

        private JsonNode readVectorUInt() throws IOException {
            int count = readInlineObjectCount("Vector<uint>");
            boolean fixed = readU8() != 0;
            ArrayNode arr = JSON.createArrayNode();
            objectRefs.add(arr);
            for (int i = 0; i < count; i++) {
                arr.add(readUInt32());
            }
            return vectorNode("Vector<uint>", fixed, arr);
        }

        private JsonNode readVectorDouble() throws IOException {
            int count = readInlineObjectCount("Vector<double>");
            boolean fixed = readU8() != 0;
            ArrayNode arr = JSON.createArrayNode();
            objectRefs.add(arr);
            for (int i = 0; i < count; i++) {
                arr.add(readDouble());
            }
            return vectorNode("Vector<double>", fixed, arr);
        }

        private JsonNode readVectorObject() throws IOException {
            int count = readInlineObjectCount("Vector<object>");
            boolean fixed = readU8() != 0;
            String typeName = readString();
            ArrayNode arr = JSON.createArrayNode();
            objectRefs.add(arr);
            for (int i = 0; i < count; i++) {
                arr.add(readValue());
            }
            ObjectNode node = vectorNode("Vector<object>", fixed, arr);
            node.put("ElementType", typeName);
            return node;
        }

        private JsonNode readDictionary() throws IOException {
            int count = readInlineObjectCount("Dictionary");
            boolean weakKeys = readU8() != 0;
            ArrayNode entries = JSON.createArrayNode();
            objectRefs.add(entries);
            for (int i = 0; i < count; i++) {
                ObjectNode entry = JSON.createObjectNode();
                entry.set("Key", readValue());
                entry.set("Value", readValue());
                entries.add(entry);
            }
            ObjectNode node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", "Dictionary");
            node.put("WeakKeys", weakKeys);
            node.set("Entries", entries);
            return node;
        }

        private int readInlineObjectCount(String type) throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                throw new IOException(type + " 引用了暂不支持的对象引用: " + (header >> 1));
            }
            return header >> 1;
        }

        private ObjectNode vectorNode(String type, boolean fixed, ArrayNode values) {
            ObjectNode node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", type);
            node.put("Fixed", fixed);
            node.set("Values", values);
            return node;
        }

        private String readString() throws IOException {
            int header = readU29();
            if ((header & 1) == 0) {
                int index = header >> 1;
                if (index < 0 || index >= stringRefs.size()) {
                    throw new IOException("AMF3 字符串引用越界: " + index);
                }
                return stringRefs.get(index);
            }
            int length = header >> 1;
            if (length == 0) {
                return "";
            }
            String text = readUtf8(length);
            stringRefs.add(text);
            return text;
        }

        private String readUtf8(int length) throws IOException {
            return new String(readBytes(length), StandardCharsets.UTF_8);
        }

        private JsonNode objectRef(int index) throws IOException {
            if (index < 0 || index >= objectRefs.size()) {
                throw new IOException("AMF3 对象引用越界: " + index);
            }
            return objectRefs.get(index);
        }

        private TraitInfo traitRef(int index) throws IOException {
            if (index < 0 || index >= traitRefs.size()) {
                throw new IOException("AMF3 traits 引用越界: " + index);
            }
            return traitRefs.get(index);
        }

        private JsonNode refNode(String type, int ref) {
            var node = JSON.createObjectNode();
            node.put("__AMF_TYPE__", type);
            node.put("__AMF_REF__", ref);
            return node;
        }

        private int readU29Signed() throws IOException {
            int value = readU29();
            if ((value & 0x10000000) != 0) {
                value |= 0xE0000000;
            }
            return value;
        }

        private int readU29() throws IOException {
            int b = readU8();
            if (b < 128) {
                return b;
            }
            int value = (b & 0x7F) << 7;
            b = readU8();
            if (b < 128) {
                return value | b;
            }
            value = (value | (b & 0x7F)) << 7;
            b = readU8();
            if (b < 128) {
                return value | b;
            }
            value = (value | (b & 0x7F)) << 8;
            return value | readU8();
        }

        private double readDouble() throws IOException {
            return Double.longBitsToDouble(readUInt64());
        }

        private int readInt32() throws IOException {
            return (int) readUInt32();
        }

        private long readUInt32() throws IOException {
            return ((long) readU8() << 24) | ((long) readU8() << 16) | ((long) readU8() << 8) | readU8();
        }

        private long readUInt64() throws IOException {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | readU8();
            }
            return value;
        }

        private byte[] readBytes(int length) throws IOException {
            if (length < 0 || pos + length > data.length) {
                throw new EOFException("AMF3 字节流提前结束");
            }
            byte[] bytes = new byte[length];
            System.arraycopy(data, pos, bytes, 0, length);
            pos += length;
            return bytes;
        }

        private int readU8() throws IOException {
            if (pos >= data.length) {
                throw new EOFException("AMF3 字节流提前结束");
            }
            return data[pos++] & 0xFF;
        }
    }

    private record TraitInfo(String className, List<String> sealedNames, boolean dynamic, boolean externalizable) {
        private TraitInfo {
            className = className == null ? "" : className;
            sealedNames = sealedNames == null ? List.of() : List.copyOf(sealedNames);
        }
    }
}
