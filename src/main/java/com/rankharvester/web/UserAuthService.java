package com.rankharvester.web;

import com.rankharvester.mail.MailTemplates;
import com.rankharvester.rank.store.UserStore;
import jakarta.mail.internet.MimeMessage;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 站点账号鉴权：注册 / 登录 / 会话（持久化 DuckDB，重启不掉线）/ 管理员邀请码。
 *
 * <ul>
 *   <li>普通用户：开放注册（极验拦机器人），登录后才能用「玩家在线查询」。</li>
 *   <li>管理员：仅凭未使用的 ADMIN 邀请码注册；邀请码由站长（TOTP 登录）或其他管理员签发。</li>
 *   <li>口令：PBKDF2WithHmacSHA256（210k 轮 + 16B 盐），格式 {@code pbkdf2$iter$salt$hash}。</li>
 * </ul>
 */
@Service
public class UserAuthService {

    private static final Logger log = LoggerFactory.getLogger(UserAuthService.class);

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    /** 会话长期有效（约 10 年）：用户要求「登录后会话永久有效」。 */
    private static final Duration SESSION_TTL = Duration.ofDays(3650);
    private static final Duration INVITE_TTL = Duration.ofDays(7);
    /** 找回密码重置链接有效期。 */
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final int PBKDF2_ITERS = 210_000;
    private static final Pattern USERNAME_RE = Pattern.compile("^[\\w\\u4e00-\\u9fa5-]{2,24}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserStore store;
    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final SecureRandom random = new SecureRandom();
    /** 发验证邮件不阻塞注册请求线程。 */
    private final ExecutorService mailExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "auth-mail");
        t.setDaemon(true);
        return t;
    });

    public UserAuthService(UserStore store, JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@example.com}") String mailFrom) {
        this.store = store;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    /** 业务结果：ok=false 时 message 给前端展示。 */
    public record Result(boolean ok, String message, String token, UserStore.SessionUser user) {
        static Result fail(String message) {
            return new Result(false, message, null, null);
        }
    }

    // ── 注册 / 登录 ───────────────────────────────────────────────────────────

    /**
     * 注册。需<b>邮箱</b>（注册后发验证邮件，留言等操作要求已验证邮箱）。
     * inviteCode 为空 → 普通用户；非空 → 必须是有效的 ADMIN 邀请码，注册成管理员。
     * 成功直接发会话（注册即登录，但邮箱未验证前不能发留言）。
     */
    public Result register(String username, String password, String email, String inviteCode, String ip) {
        String name = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim();
        if (!USERNAME_RE.matcher(name).matches()) {
            return Result.fail("用户名 2-24 位，仅限中文、字母、数字、下划线、连字符");
        }
        if (password == null || password.length() < 8 || password.length() > 72) {
            return Result.fail("密码长度需在 8-72 位之间");
        }
        if (mail.isEmpty() || mail.length() > 254 || !EMAIL_RE.matcher(mail).matches()) {
            return Result.fail("请填写有效邮箱（用于邮箱验证与通知）");
        }
        if (store.findByUsername(name) != null) {
            return Result.fail("用户名已被占用");
        }
        String role = ROLE_USER;
        String invitedBy = null;
        String code = inviteCode == null ? "" : inviteCode.trim();
        if (!code.isEmpty()) {
            var inv = store.findInvite(code);
            long now = System.currentTimeMillis();
            if (inv == null || inv.revoked() || inv.usedBy() != null || inv.expiresAt() <= now) {
                return Result.fail("邀请码无效、已使用或已过期");
            }
            if (!store.useInvite(code, name, now)) {
                return Result.fail("邀请码刚被使用，请联系签发人重新生成");
            }
            role = inv.role();
            invitedBy = inv.createdBy();
        }
        long now = System.currentTimeMillis();
        String token = randomToken("vrf_");
        long id = store.createUser(name, hashPassword(password), role, invitedBy, mail, token, now);
        if (id < 0) {
            return Result.fail("用户名已被占用");
        }
        log.info("[Auth] 新用户注册：{}（id={}，role={}，待邮箱验证）", name, id, role);
        sendVerifyEmail(mail, name, token);
        return issueSession(id, ip);
    }

    /** 凭 token 验证邮箱。@return 是否成功。 */
    public boolean verifyEmail(String token) {
        return token != null && !token.isBlank() && store.verifyEmail(token.trim());
    }

    /** 重发验证邮件（登录用户用）。 */
    public Result resendVerify(long userId) {
        var u = store.findById(userId);
        if (u == null) {
            return Result.fail("用户不存在");
        }
        if (u.emailVerified()) {
            return new Result(true, "邮箱已验证，无需重复", null, null);
        }
        if (u.email() == null || u.email().isBlank()) {
            return Result.fail("该账号未绑定邮箱，请到个人中心「绑定邮箱」后再验证");
        }
        String token = randomToken("vrf_");
        store.setVerifyToken(userId, token);
        sendVerifyEmail(u.email(), u.username(), token);
        return new Result(true, "验证邮件已重新发送", null, null);
    }

    private String randomToken(String prefix) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return prefix + HexFormat.of().formatHex(raw);
    }

    private void sendVerifyEmail(String email, String username, String token) {
        mailExecutor.submit(() -> {
            String link = "https://example.com/verify?token=" + token;
            String body = MailTemplates.paragraph("你好，" + MailTemplates.escapeHtml(username) + "：")
                    + MailTemplates.paragraph("感谢注册 example.com。请点击下方按钮完成<b>邮箱验证</b>，"
                            + "验证后即可在留言板发言。")
                    + MailTemplates.button("验证邮箱", link)
                    + MailTemplates.small("按钮无法点击时，复制此链接到浏览器打开：<br>" + MailTemplates.escapeHtml(link))
                    + MailTemplates.small("如果这不是你的操作，请忽略本邮件。");
            try {
                MimeMessage mime = mailSender.createMimeMessage();
                var helper = new MimeMessageHelper(mime, false, "UTF-8");
                helper.setFrom(mailFrom);
                helper.setTo(email);
                helper.setSubject("【strikegod】请验证你的邮箱");
                helper.setText(MailTemplates.wrap("验证你的邮箱", "✉️ 邮箱验证", body), true);
                mailSender.send(mime);
                log.info("[Auth] 验证邮件已发送 → {}", email);
            } catch (Exception e) {
                log.warn("[Auth] 验证邮件发送失败({}): {}", email, e.getMessage());
            }
        });
    }

    public Result login(String username, String password, String ip) {
        String name = username == null ? "" : username.trim();
        var user = name.isEmpty() ? null : store.findByUsername(name);
        if (user == null || !verifyPassword(password, user.passwordHash())) {
            return Result.fail("用户名或密码错误");
        }
        if (user.disabled()) {
            return Result.fail("账号已被停用");
        }
        store.touchLogin(user.id(), System.currentTimeMillis());
        return issueSession(user.id(), ip);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            store.deleteSession(token);
        }
    }

    /**
     * 自助改密码：校验旧密码 → 写新哈希 → 踢掉除当前会话外的其他登录设备。
     *
     * @param keepToken 当前会话 token（保留，不强迫本设备重新登录）
     */
    public Result changePassword(long userId, String oldPassword, String newPassword, String keepToken) {
        var u = store.findById(userId);
        if (u == null || u.disabled()) {
            return Result.fail("账号不存在或已停用");
        }
        if (!verifyPassword(oldPassword, u.passwordHash())) {
            return Result.fail("当前密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
            return Result.fail("新密码长度需在 8-72 位之间");
        }
        if (newPassword.equals(oldPassword)) {
            return Result.fail("新密码不能与当前密码相同");
        }
        if (!store.updatePassword(userId, hashPassword(newPassword))) {
            return Result.fail("密码更新失败，请稍后再试");
        }
        store.deleteSessionsExcept(userId, keepToken);
        log.info("[Auth] 用户改密码：{}（id={}，其他会话已注销）", u.username(), userId);
        return new Result(true, "密码已修改，其他设备已退出登录", null, null);
    }

    /**
     * 找回密码第一步：按用户名找账号，向其绑定邮箱发送重置链接（30 分钟有效）。
     * 防枚举：无论用户名是否存在/是否绑邮箱，一律返回同一成功文案。
     */
    public Result forgotPassword(String username) {
        String generic = "若该用户名存在且绑定了邮箱，重置链接已发送到邮箱（30 分钟内有效）";
        String name = username == null ? "" : username.trim();
        var u = name.isEmpty() ? null : store.findByUsername(name);
        if (u == null || u.disabled() || u.email() == null || u.email().isBlank()) {
            return new Result(true, generic, null, null);
        }
        String token = randomToken("rst_");
        store.setResetToken(u.id(), token, System.currentTimeMillis() + RESET_TTL.toMillis());
        sendResetEmail(u.email(), u.username(), token);
        log.info("[Auth] 找回密码邮件已发：{}（id={}）", u.username(), u.id());
        return new Result(true, generic, null, null);
    }

    /** 找回密码第二步：凭未过期的重置 token 设新密码，作废 token 并注销该用户全部会话。 */
    public Result resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            return Result.fail("重置链接无效");
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
            return Result.fail("新密码长度需在 8-72 位之间");
        }
        var u = store.findByResetToken(token.trim(), System.currentTimeMillis());
        if (u == null) {
            return Result.fail("重置链接无效或已过期，请重新发起找回");
        }
        store.updatePassword(u.id(), hashPassword(newPassword));
        store.clearResetToken(u.id());
        store.deleteSessionsExcept(u.id(), "");
        log.info("[Auth] 密码已重置：{}（id={}，全部会话已注销）", u.username(), u.id());
        return new Result(true, "密码已重置，请用新密码登录", null, null);
    }

    /** 退出其他全部设备（保留当前会话）。 */
    public Result logoutOthers(long userId, String keepToken) {
        store.deleteSessionsExcept(userId, keepToken);
        return new Result(true, "其他设备已全部退出登录", null, null);
    }

    /** 注销账号（软删除）：校验密码 → 停用账号 + 注销全部会话。可由管理员恢复。 */
    public Result deleteAccount(long userId, String password) {
        var u = store.findById(userId);
        if (u == null) {
            return Result.fail("账号不存在");
        }
        if (!verifyPassword(password, u.passwordHash())) {
            return Result.fail("密码不正确");
        }
        store.deleteSessionsExcept(userId, "");
        store.setDisabled(userId, true);
        log.info("[Auth] 账号注销（停用）：{}（id={}）", u.username(), userId);
        return new Result(true, "账号已注销。如需恢复请通过留言板联系管理员", null, null);
    }

    /** 用户完整档案（个人中心展示注册时间/上次登录用）。 */
    public UserStore.User profile(long userId) {
        return store.findById(userId);
    }

    private void sendResetEmail(String email, String username, String token) {
        mailExecutor.submit(() -> {
            String link = "https://example.com/reset?token=" + token;
            String body = MailTemplates.paragraph("你好，" + MailTemplates.escapeHtml(username) + "：")
                    + MailTemplates.paragraph("我们收到了你的<b>找回密码</b>请求。点击下方按钮设置新密码"
                            + "（链接 30 分钟内有效，使用一次后失效）。")
                    + MailTemplates.button("重置密码", link)
                    + MailTemplates.small("按钮无法点击时，复制此链接到浏览器打开：<br>" + MailTemplates.escapeHtml(link))
                    + MailTemplates.small("如果这不是你的操作，请忽略本邮件，你的密码不会被改变。");
            try {
                MimeMessage mime = mailSender.createMimeMessage();
                var helper = new MimeMessageHelper(mime, false, "UTF-8");
                helper.setFrom(mailFrom);
                helper.setTo(email);
                helper.setSubject("【strikegod】重置你的密码");
                helper.setText(MailTemplates.wrap("重置你的密码", "🔑 找回密码", body), true);
                mailSender.send(mime);
                log.info("[Auth] 重置密码邮件已发送 → {}", email);
            } catch (Exception e) {
                log.warn("[Auth] 重置密码邮件发送失败({}): {}", email, e.getMessage());
            }
        });
    }

    /**
     * 自助换绑邮箱：校验登录密码 → 写新邮箱（置未验证）→ 发验证邮件。
     */
    public Result changeEmail(long userId, String password, String newEmail) {
        var u = store.findById(userId);
        if (u == null || u.disabled()) {
            return Result.fail("账号不存在或已停用");
        }
        if (!verifyPassword(password, u.passwordHash())) {
            return Result.fail("密码不正确");
        }
        String mail = newEmail == null ? "" : newEmail.trim();
        if (mail.isEmpty() || mail.length() > 254 || !EMAIL_RE.matcher(mail).matches()) {
            return Result.fail("请填写有效邮箱");
        }
        if (mail.equalsIgnoreCase(u.email() == null ? "" : u.email().trim()) && u.emailVerified()) {
            return Result.fail("新邮箱与当前邮箱相同");
        }
        String token = randomToken("vrf_");
        if (!store.updateEmail(userId, mail, token)) {
            return Result.fail("邮箱更新失败，请稍后再试");
        }
        sendVerifyEmail(mail, u.username(), token);
        log.info("[Auth] 用户换绑邮箱：{}（id={}，待重新验证）", u.username(), userId);
        return new Result(true, "邮箱已更换，验证邮件已发送到新邮箱，请查收完成验证", null, null);
    }

    /** 会话校验；无效/过期/停用返回 null。 */
    public UserStore.SessionUser session(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        var s = store.findSession(token, System.currentTimeMillis());
        return (s == null || s.disabled()) ? null : s;
    }

    /** 是否有效管理员会话。 */
    public boolean isAdmin(String token) {
        var s = session(token);
        return s != null && ROLE_ADMIN.equals(s.role());
    }

    private Result issueSession(long userId, String ip) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = "su_" + HexFormat.of().formatHex(raw);
        long now = System.currentTimeMillis();
        store.createSession(token, userId, now, now + SESSION_TTL.toMillis(), ip);
        var s = store.findSession(token, now);
        return new Result(true, "ok", token, s);
    }

    // ── 邀请码 ────────────────────────────────────────────────────────────────

    /** 签发一个 7 天有效的一次性 ADMIN 邀请码。 */
    public UserStore.Invite createAdminInvite(String note, String createdBy) {
        byte[] raw = new byte[12];
        random.nextBytes(raw);
        String code = "inv_" + HexFormat.of().formatHex(raw);
        long now = System.currentTimeMillis();
        store.createInvite(code, ROLE_ADMIN, note == null ? "" : note.trim(), createdBy, now,
                now + INVITE_TTL.toMillis());
        return store.findInvite(code);
    }

    public List<UserStore.Invite> listInvites() {
        return store.listInvites();
    }

    public boolean revokeInvite(String code) {
        return store.revokeInvite(code);
    }

    public List<UserStore.User> listUsers() {
        return store.listUsers();
    }

    public boolean setUserDisabled(long userId, boolean disabled) {
        return store.setDisabled(userId, disabled);
    }

    /** 用户是否存在且未被停用（API key 通道据此判断归属账号有效性）。 */
    public boolean isUserActive(long userId) {
        var u = store.findById(userId);
        return u != null && !u.disabled();
    }

    // ── 口令哈希 ──────────────────────────────────────────────────────────────

    String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERS);
        var hex = HexFormat.of();
        return "pbkdf2$" + PBKDF2_ITERS + "$" + hex.formatHex(salt) + "$" + hex.formatHex(hash);
    }

    boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        try {
            var hex = HexFormat.of();
            int iters = Integer.parseInt(parts[1]);
            byte[] salt = hex.parseHex(parts[2]);
            byte[] expect = hex.parseHex(parts[3]);
            byte[] actual = pbkdf2(password, salt, iters);
            return java.security.MessageDigest.isEqual(expect, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iters) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iters, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 失败", e);
        }
    }

    // ── 维护 ─────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60_000L)
    void purgeExpiredSessions() {
        store.purgeExpired(System.currentTimeMillis());
    }
}
