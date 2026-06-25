package com.strikegod.engine.gateway.game.activity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * activityList.xml 中单个 {@code <child>} 节点解析出的活动上下文。
 *
 * <p>活动 SWF 的按钮状态、动态文本、奖励配置都来自 activityList.xml。渲染管线必须携带这份元数据，
 * 避免只根据 SWF 文件名或裸 SWF 结构做孤立推断。
 */
public record ActivityXmlMetadata(
        int activityId,
        String activityIds,
        String btnIndex,
        String url,
        String description,
        String startTime,
        String endTime,
        Map<String, String> runtimeTexts) {

    public ActivityXmlMetadata {
        runtimeTexts = runtimeTexts == null ? Map.of() : Map.copyOf(runtimeTexts);
    }

    public String title() {
        if (btnIndex != null && !btnIndex.isBlank()) {
            return btnIndex;
        }
        if (description != null && !description.isBlank()) {
            return description;
        }
        return "活动 " + activityId;
    }

    public ActivityXmlMetadata withUrl(String nextUrl) {
        return new ActivityXmlMetadata(
                activityId, activityIds, btnIndex, nextUrl, description, startTime, endTime, runtimeTexts);
    }

    public String displaysSummary() {
        return runtimeTexts.get("__displaysSummary");
    }

    public String rewardAlertsSummary() {
        return runtimeTexts.get("__rewardAlertsSummary");
    }

    public Map<String, String> displayTextDefaults() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : runtimeTexts.entrySet()) {
            if (!entry.getKey().startsWith("__")
                    && entry.getValue() != null
                    && !entry.getValue().isBlank()) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    public List<Integer> linkedActivityIds() {
        String raw = runtimeTexts.get("__linkedActivityIds");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    public int sortIndex() {
        String raw = runtimeTexts.get("__sortIndex");
        if (raw == null || raw.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
