package com.rankharvester.net;

import com.rankharvester.account.GameAccount;

/**
 * 握手凭据提供者 SPI。
 *
 * <p>真实环境实现：对接 PAuth 同步 + 大厅 flashvars 解析 + 验证码 OCR，产出
 * {@link HandshakeCredentials}。本项目默认提供一个抛异常的占位实现
 * （仅在 {@code dryRun=false} 且未配置真实实现时触发），明确提示需接入。
 */
public interface CredentialProvider {

    /**
     * 为账号在指定大区获取 loginV3 握手凭据。
     *
     * @throws UnsupportedOperationException 未接入真实凭据来源时
     */
    HandshakeCredentials obtain(GameAccount account, String server);
}
