package com.rankharvester.net;

import com.rankharvester.account.GameAccount;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实凭据提供者：用账号 pauth 抓 flashvars（直连），组装 loginV3 所需凭据 + extParams（AMF 匿名对象）。
 *
 * <p>不含代理。pauth 失效会导致 flashvars 抓取失败（需另行刷新 pauth，本类不负责）。
 */
public final class FlashvarsCredentialProvider implements CredentialProvider {

    private final FlashvarsClient flashvars;
    private final String microVersion;
    private final String deviceInfo;

    public FlashvarsCredentialProvider(FlashvarsClient flashvars, String microVersion, String deviceInfo) {
        this.flashvars = flashvars;
        this.microVersion = microVersion;
        this.deviceInfo = deviceInfo;
    }

    @Override
    public HandshakeCredentials obtain(GameAccount account, String server) {
        int serverId = GameServer.fromIdOrThrow(parseId(server)).id();
        var fv = flashvars.fetch(account.pauth(), serverId);
        Map<String, Object> extParams = buildExtParams(fv.loginUrl(), fv.clientIp());
        return new HandshakeCredentials(
                fv.username(),
                parseInt(fv.isMicroClient(), 1),
                fv.timestamp(),
                fv.sign(),
                parseInt(fv.isadult(), 0),
                extParams);
    }

    /** extParams 作为 AMF 匿名对象发送；字段顺序：loginUrl→clientIp→isMicroClient→microVersion→deviceInfo。 */
    private Map<String, Object> buildExtParams(String loginUrl, String clientIp) {
        var m = new LinkedHashMap<String, Object>(8);
        m.put("loginUrl", loginUrl == null ? "" : loginUrl);
        m.put("clientIp", clientIp == null ? "" : clientIp);
        m.put("isMicroClient", 1);
        m.put("microVersion", microVersion);
        m.put("deviceInfo", deviceInfo);
        return m;
    }

    private static int parseId(String server) {
        try {
            return Integer.parseInt(server.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("登录需要数字服号，收到: " + server);
        }
    }

    private static int parseInt(String s, int dft) {
        try {
            return s == null ? dft : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
