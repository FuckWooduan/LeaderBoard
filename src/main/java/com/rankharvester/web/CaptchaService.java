package com.rankharvester.web;

import org.springframework.stereotype.Service;

/**
 * 人机验证统一门面：Cloudflare Turnstile 与极验 GeeTest 并列，<b>任一通过即放行</b>。
 *
 * <p>前端弹窗默认走极验（对中国用户友好），可切到 Turnstile；两条路径分别把一次性凭据放进
 * 请求体的 {@code gtPayload}（极验）或 {@code cfToken}（Turnstile）。后端各控制器统一调用
 * {@link #verify(String, String)}，无需关心用户选了哪种。
 *
 * <p>语义：两种验证都未启用 → 直接放行（沿用旧的「Turnstile 未配置即不拦」行为）；否则用户必须
 * 至少提交一种<b>有效</b>凭据。单种验证服务不可达时其 verify 自身 fail-open。
 */
@Service
public class CaptchaService {

    private final TurnstileService turnstile;
    private final GeetestService geetest;

    public CaptchaService(TurnstileService turnstile, GeetestService geetest) {
        this.turnstile = turnstile;
        this.geetest = geetest;
    }

    public boolean turnstileEnabled() {
        return turnstile.enabled();
    }

    public String turnstileSiteKey() {
        return turnstile.siteKey();
    }

    public boolean geetestEnabled() {
        return geetest.enabled();
    }

    public String geetestCaptchaId() {
        return geetest.captchaId();
    }

    /**
     * @param cfToken   Turnstile 一次性 token（请求体 {@code cfToken}），用户未选则为空
     * @param gtPayload 极验 getValidate() 的 JSON 串（请求体 {@code gtPayload}），用户未选则为空
     * @return 任一已启用验证通过即 true；两者皆未启用也 true（不拦）
     */
    public boolean verify(String cfToken, String gtPayload) {
        if (!turnstile.enabled() && !geetest.enabled()) {
            return true;
        }
        // enabled() 守卫不可省：未启用的验证器其 verify() 恒返回 true（fail-open），
        // 若省略，攻击者可在另一种验证关闭时塞个假 token 绕过仍开启的那种。
        if (geetest.enabled() && gtPayload != null && !gtPayload.isBlank() && geetest.verify(gtPayload)) {
            return true;
        }
        if (turnstile.enabled() && cfToken != null && !cfToken.isBlank() && turnstile.verify(cfToken)) {
            return true;
        }
        return false;
    }
}
