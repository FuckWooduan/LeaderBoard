package com.rankharvester.net;

import com.rankharvester.account.GameAccount;

/**
 * 默认占位凭据提供者：未接入真实 PAuth / flashvars 时抛异常，明确提示。
 * 仅在 {@code dry-run=false} 且未提供自定义 {@link CredentialProvider} 时触发。
 */
public final class UnconfiguredCredentialProvider implements CredentialProvider {

    @Override
    public HandshakeCredentials obtain(GameAccount account, String server) {
        throw new UnsupportedOperationException(
                "未配置 CredentialProvider：真实登录需对接 PAuth 同步 + 大厅 flashvars 解析。"
                        + " 当前可用 rankharvester.game.dry-run=true 以模拟模式运行。");
    }
}
