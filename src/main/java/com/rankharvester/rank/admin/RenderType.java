package com.rankharvester.rank.admin;

/**
 * 字段渲染类型——决定前端如何展示该列的值。
 *
 * <ul>
 *   <li>{@link #TEXT} 原样文本（默认）。</li>
 *   <li>{@link #NUMBER} 数字，按<b>名次百分位</b>自动分档（前 10% / 25% / 50% / 其余）着色——
 *       档位由后端用 {@code rank/total} 算，前端按档套样式。</li>
 *   <li>{@link #REALM} 境界等级：0=荣光之主 / 1=恒星之主 / 2=星界域主，带颜色 tag。</li>
 *   <li>{@link #VIP} VIP 等级：0~6 一档、7~10 一档、11..17 各一档，带颜色 tag。</li>
 *   <li>{@link #DISTRICT} 区号 → 区服名（如 18→联通五区），按运营商（电信/联通/双线）着色 tag。</li>
 * </ul>
 */
public enum RenderType {
    TEXT,
    NUMBER,
    REALM,
    VIP,
    DISTRICT,
    /** 长文本：列表里只显示前 10 字 + 省略号，点击弹窗看全文（战队宣言/公告等）。 */
    LONGTEXT
}
