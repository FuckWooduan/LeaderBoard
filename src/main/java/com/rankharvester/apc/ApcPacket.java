package com.rankharvester.apc;

/**
 * 解析后的 APC 数据包元信息：偏移、大小、压缩状态、错误、解析出的 {@link ApcObject}。
 * 由 {@link ApcCodec#parseHexStream(String)} / {@link ApcCodec#parseByteStream(byte[])} 返回。
 */
public final class ApcPacket {

    private int offset;
    private int totalSize;
    private boolean compressed;
    private boolean hasError;
    private String error;
    private ApcObject apcObj;

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(int totalSize) {
        this.totalSize = totalSize;
    }

    public boolean isCompressed() {
        return compressed;
    }

    public void setCompressed(boolean compressed) {
        this.compressed = compressed;
    }

    public boolean isHasError() {
        return hasError;
    }

    public void setHasError(boolean hasError) {
        this.hasError = hasError;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public ApcObject getApcObj() {
        return apcObj;
    }

    public void setApcObj(ApcObject apcObj) {
        this.apcObj = apcObj;
    }
}
