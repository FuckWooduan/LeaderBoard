package com.rankharvester.web;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 后台双重鉴权（单管理员）：口令 + 动态安全码（TOTP/RFC6238，可选）。
 *
 * <ul>
 *   <li>口令：{@code rankharvester.admin.password}（env {@code RANKHARVESTER_ADMIN_PASSWORD}）。</li>
 *   <li>安全锁：{@code rankharvester.admin.totp-secret}（env {@code RANKHARVESTER_ADMIN_TOTPSECRET}）为 base32 密钥；
 *       非空时登录必须再输入 Google Authenticator / 任意 TOTP App 的 6 位动态码。为空则只校验口令。</li>
 * </ul>
 *
 * <p>登录成功发内存 token（7 天，重启失效）。仅保护 {@code /api/boards/**}。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final long TTL_MS = Duration.ofDays(7).toMillis();
    private static final int TOTP_STEP = 30; // 秒
    private static final int TOTP_DIGITS = 6;

    private final String totpSecret; // base32；空=不启用安全码（登录将永远失败）
    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    public AdminAuthService(
            @Value("${rankharvester.admin.totp-secret:}") String totpSecret) {
        this.totpSecret = totpSecret == null ? "" : totpSecret.trim();
    }

    @PostConstruct
    void logSetup() {
        if (!totpSecret.isBlank()) {
            String otpauth = "otpauth://totp/StrikeGod%20Admin?secret=" + totpSecret + "&issuer=StrikeGod&digits=6&period=30";
            log.info("[Admin] 安全锁已启用（TOTP）。把以下密钥加入身份验证器 App：secret={} ；或扫码 URL：{}", totpSecret, otpauth);
        } else {
            log.info("[Admin] 安全锁未启用（仅口令）。设 RANKHARVESTER_ADMIN_TOTPSECRET 开启。");
        }
    }

    /** 仅校验 6 位动态安全码（TOTP），成功返回新 token，失败 null。 */
    public String login(String otp) {
        if (totpSecret.isBlank() || !verifyTotp(otp)) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + TTL_MS);
        return token;
    }

    public boolean totpEnabled() {
        return !totpSecret.isBlank();
    }

    public boolean valid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long exp = tokens.get(token);
        if (exp == null) {
            return false;
        }
        if (exp < System.currentTimeMillis()) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    // ── TOTP（RFC6238, HMAC-SHA1, 容许 ±1 时间窗）──────────────────────────────
    private boolean verifyTotp(String otp) {
        if (otp == null) {
            return false;
        }
        String code = otp.trim();
        if (code.length() != TOTP_DIGITS) {
            return false;
        }
        byte[] key = base32Decode(totpSecret);
        long t = System.currentTimeMillis() / 1000L / TOTP_STEP;
        for (long w = -1; w <= 1; w++) {
            if (code.equals(totpAt(key, t + w))) {
                return true;
            }
        }
        return false;
    }

    private static String totpAt(byte[] key, long counter) {
        try {
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int otp = bin % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] base32Decode(String s) {
        String in = s.replace("=", "").toUpperCase().replaceAll("[^A-Z2-7]", "");
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        for (char c : in.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
