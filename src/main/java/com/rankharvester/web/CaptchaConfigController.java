package com.rankharvester.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端取人机验证配置（公钥/captchaId 可下发，secret/key 仅服务端用）。
 *
 * <p>返回 Turnstile 与极验 GeeTest 两套配置，前端据此决定弹窗默认走哪种、能否切换。
 */
@RestController
public class CaptchaConfigController {

    private final CaptchaService captcha;

    public CaptchaConfigController(CaptchaService captcha) {
        this.captcha = captcha;
    }

    @GetMapping("/api/public/captcha-config")
    public Map<String, Object> config() {
        return Map.of(
                "turnstile", Map.of(
                        "enabled", captcha.turnstileEnabled(),
                        "siteKey", captcha.turnstileSiteKey()),
                "geetest", Map.of(
                        "enabled", captcha.geetestEnabled(),
                        "captchaId", captcha.geetestCaptchaId()));
    }
}
