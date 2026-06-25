package com.rankharvester.rank.admin;

/**
 * 某榜某字段的后台展示配置。
 *
 * @param field    数据列名（落地表列名，如 {@code score_number}）
 * @param label    自定义显示名（为空时回退默认中文 / 字段键）
 * @param order    列显示顺序（升序从左到右；名次列 {@code rank} 永远第一、不受此控制）
 * @param visible  该列是否显示（<b>必须显式打开</b>才在前端出现；名次列始终显示）
 * @param render   渲染类型（数字分档 / 境界 / VIP / 文本）
 */
public record BoardFieldConfig(
        String field,
        String label,
        int order,
        boolean visible,
        RenderType render) {}
