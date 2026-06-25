package com.rankharvester.net;

import java.util.Map;

/**
 * loginV3 握手所需的凭据，来自大厅服 flashvars + 设备 extParams。
 *
 * <p>这些字段在真实环境中由 PAuth 同步 + 大厅页解析获得（见主仓 Engine 的
 * GameAccountPauthService / Flashvars）。本项目把它们抽象为一个 record，
 * 由 {@link CredentialProvider} 提供，登录逻辑与凭据来源解耦。
 *
 * @param username      游戏用户名
 * @param isMicroClient 微端标志（0/1）
 * @param timestamp     签名时间戳
 * @param sign          大厅服签名
 * @param isAdult       成人标志（0/1）
 * @param extParams     扩展参数（AMF 匿名对象，按 loginUrl/clientIp/isMicroClient/microVersion/deviceInfo 顺序）
 */
public record HandshakeCredentials(
        String username, int isMicroClient, String timestamp, String sign, int isAdult, Map<String, Object> extParams) {}
