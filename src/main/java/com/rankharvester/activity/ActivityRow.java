package com.rankharvester.activity;

/**
 * 一条活动（res_config → activityList XML 的 {@code <child>} 节点解析后的结构化行）。
 *
 * @param activityId  活动唯一 ID（{@code <id>}，推送去重键）
 * @param activityIds 具体活动 ID 列表（{@code <activity>}，可空）
 * @param title       标题/按钮名（{@code <btnIndex>}）
 * @param description 描述（{@code <description>}，可能含 HTML，推送时剥标签）
 * @param url         活动资源标识（{@code <url>}，多为 SWF 相对路径，仅内部用，不推给用户）
 * @param startTime   开始时间字符串（{@code <condition startTime>}，形如 yyyy-MM-dd HH:mm:ss，可空）
 * @param endTime     结束时间字符串（{@code <condition endTime>}，可空）
 */
public record ActivityRow(
        long activityId,
        String activityIds,
        String title,
        String description,
        String url,
        String startTime,
        String endTime) {}
