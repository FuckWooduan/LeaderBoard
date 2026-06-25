package com.rankharvester.apc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * APC 协议对象：函数名 + 跟踪标志 + 参数列表。
 *
 * <p>从 AMF3 反序列化后的结构化表示。纯 JDK，无第三方依赖。
 */
public final class ApcObject {

    private String functionName = "";
    private boolean isTrace = true;
    private List<Object> parameters = new ArrayList<>();

    public ApcObject() {}

    public ApcObject(String functionName, List<Object> parameters, boolean isTrace) {
        this.functionName = functionName == null ? "" : functionName;
        this.parameters = parameters == null ? new ArrayList<>() : new ArrayList<>(parameters);
        this.isTrace = isTrace;
    }

    /** 静态工厂：方法名 + 变长参数快速构造（isTrace 默认 true）。 */
    public static ApcObject of(String functionName, Object... params) {
        var list = new ArrayList<Object>();
        if (params != null) {
            Collections.addAll(list, params);
        }
        return new ApcObject(functionName, list, true);
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public boolean isTrace() {
        return isTrace;
    }

    public void setTrace(boolean trace) {
        isTrace = trace;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public void setParameters(List<Object> parameters) {
        this.parameters = parameters;
    }

    /** 取第 index 个参数并按类型转换，越界或类型不符返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T param(int index) {
        if (parameters == null || index < 0 || index >= parameters.size()) {
            return null;
        }
        try {
            return (T) parameters.get(index);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** 直接编码为 hex 字符串（默认压缩）。 */
    public String toHex() {
        return ApcCodec.buildPacketHex(functionName, parameters, isTrace, true);
    }

    public String toHex(boolean compress) {
        return ApcCodec.buildPacketHex(functionName, parameters, isTrace, compress);
    }

    public byte[] toBytes() {
        return HexFormat.of().parseHex(toHex());
    }

    public byte[] toBytes(boolean compress) {
        return HexFormat.of().parseHex(toHex(compress));
    }

    @Override
    public String toString() {
        return functionName + "("
                + String.join(
                        ", ",
                        parameters.stream()
                                .map(p -> p == null ? "null" : p.toString())
                                .toList())
                + ")";
    }
}
