package com.rankharvester.mail;

/**
 * 全站统一邮件模板（Google Material 风格）。
 *
 * <p>视觉规范：浅灰画布 + 居中 600px 白卡片（16px 圆角、细描边）、Google Sans/Roboto 字体栈、
 * Google 蓝 {@code #1a73e8} 品牌色、pill 按钮、灰色小字 footer。所有样式内联（邮件客户端兼容），
 * 不依赖外部资源，深浅色客户端均可读。
 *
 * <p>用法：正文用 {@link #paragraph}/{@link #quote}/{@link #button}/{@link #noteBox} 等组件拼出
 * bodyHtml，再 {@link #wrap} 成完整 HTML。
 */
public final class MailTemplates {

    private MailTemplates() {}

    /** 品牌蓝（Google Blue 600）。 */
    public static final String BLUE = "#1a73e8";
    private static final String TEXT = "#3c4043";
    private static final String MUTED = "#5f6368";
    private static final String FAINT = "#9aa0a6";
    private static final String CANVAS = "#f6f8fc";
    private static final String BORDER = "#e0e3e7";
    private static final String FONT =
            "'Google Sans',Roboto,system-ui,-apple-system,'PingFang SC','Microsoft YaHei','Segoe UI',sans-serif";

    /**
     * 完整邮件 HTML。
     *
     * @param preheader 收件箱预览文案（正文里不可见）
     * @param title     卡片大标题
     * @param bodyHtml  卡片正文（由本类组件拼装）
     */
    public static String wrap(String preheader, String title, String bodyHtml) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:%CANVAS%;-webkit-font-smoothing:antialiased">
                <div style="display:none;max-height:0;overflow:hidden;mso-hide:all">%PREHEADER%</div>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:%CANVAS%;padding:32px 12px">
                <tr><td align="center">
                  <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                         style="max-width:600px;width:100%;background:#ffffff;border:1px solid %BORDER%;border-radius:16px;overflow:hidden">
                    <tr><td style="padding:28px 40px 0;font-family:%FONT%">
                      <div style="font-size:15px;font-weight:700;letter-spacing:.2px">
                        <span style="color:%BLUE%">▦ strikegod</span><span style="color:%FAINT%">.com</span>
                        <span style="color:%FAINT%;font-weight:400;font-size:12px">&nbsp;·&nbsp;生死狙击排行榜</span>
                      </div>
                    </td></tr>
                    <tr><td style="padding:22px 40px 8px;font-family:%FONT%">
                      <div style="font-size:22px;line-height:1.4;font-weight:500;color:#202124">%TITLE%</div>
                    </td></tr>
                    <tr><td style="padding:4px 40px 32px;font-family:%FONT%;font-size:14px;line-height:1.75;color:%TEXT%">
                      %BODY%
                    </td></tr>
                  </table>
                  <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%">
                    <tr><td align="center" style="padding:20px 24px;font-family:%FONT%;font-size:12px;line-height:1.8;color:%FAINT%">
                      此邮件由 <a href="https://example.com" style="color:%FAINT%">example.com</a> 系统自动发出，请勿直接回复。<br>
                      公益免费 · 离线可用 · 全站开放 <a href="https://example.com/api-docs" style="color:%FAINT%">API</a>
                    </td></tr>
                  </table>
                </td></tr>
                </table>
                </body></html>
                """
                .replace("%PREHEADER%", escapeHtml(preheader == null ? "" : preheader))
                .replace("%TITLE%", title)
                .replace("%BODY%", bodyHtml)
                .replace("%CANVAS%", CANVAS)
                .replace("%BORDER%", BORDER)
                .replace("%BLUE%", BLUE)
                .replace("%FAINT%", FAINT)
                .replace("%TEXT%", TEXT)
                .replace("%FONT%", FONT);
    }

    /** 普通段落。 */
    public static String paragraph(String html) {
        return "<p style=\"margin:10px 0\">" + html + "</p>";
    }

    /** 灰色说明小字。 */
    public static String small(String html) {
        return "<p style=\"margin:10px 0;font-size:12.5px;color:" + MUTED + "\">" + html + "</p>";
    }

    /** 引用块（展示留言原文等）。accent 为左侧色条，如 #c5221f（红）/ #1a73e8（蓝）。 */
    public static String quote(String text, String accent) {
        return "<div style=\"margin:14px 0;padding:12px 16px;background:#f8f9fa;border-left:3px solid " + accent
                + ";border-radius:0 8px 8px 0;white-space:pre-wrap;word-break:break-word;color:" + TEXT + "\">"
                + escapeHtml(text) + "</div>";
    }

    /** 主按钮（pill，Google 蓝）。 */
    public static String button(String label, String url) {
        return "<div style=\"margin:22px 0 6px\"><a href=\"" + url + "\" "
                + "style=\"display:inline-block;background:" + BLUE + ";color:#ffffff;text-decoration:none;"
                + "font-weight:600;font-size:14px;padding:10px 28px;border-radius:999px\">" + escapeHtml(label)
                + "</a></div>";
    }

    /** 信息提示框（浅蓝底）。 */
    public static String noteBox(String html) {
        return "<div style=\"margin:14px 0;padding:12px 16px;background:#e8f0fe;border-radius:8px;"
                + "font-size:13px;color:#174ea6;line-height:1.7\">" + html + "</div>";
    }

    /** 键值行（标签灰、值深色）。 */
    public static String kv(String label, String valueHtml) {
        return "<div style=\"margin:4px 0\"><span style=\"color:" + MUTED + "\">" + escapeHtml(label)
                + "</span>&nbsp;&nbsp;<span style=\"color:#202124;font-weight:500\">" + valueHtml + "</span></div>";
    }

    /** 细分隔线。 */
    public static String divider() {
        return "<hr style=\"border:none;border-top:1px solid " + BORDER + ";margin:20px 0\">";
    }

    public static String escapeHtml(String s) {
        return s == null ? ""
                : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
