package com.rankharvester.net;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 游戏连接配置。
 *
 * <p>{@code dryRun=true}（默认）时不开真实 socket，由 {@link SimulatedGameClient} 产出合成榜数据，
 * 便于在没有真实游戏服 / 完整登录栈（PAuth、flashvars、验证码 OCR）的情况下端到端跑通
 * 「调度 → 抓取 → 解析 → 存储 → 推送」。接入真实环境时置为 false 并实现 {@link CredentialProvider}。
 */
@ConfigurationProperties(prefix = "rankharvester.game")
public class GameProperties {

    /** 是否模拟模式（不连真实游戏服）。 */
    private boolean dryRun = true;

    /** TCP 连接超时（毫秒）。 */
    private int connectTimeoutMs = 10_000;

    /** 心跳间隔（秒），与微端一致默认 20s。 */
    private int heartbeatSeconds = 20;

    /** 单次请求等待回包超时（秒）。 */
    private int callTimeoutSeconds = 15;

    /** 大区名 → host:port 映射（留空则用内置 GameServer 表按服号解析）。 */
    private Map<String, String> servers = new HashMap<>();

    /** 验证码 OCR 服务地址（multipart POST，字段名 image）。 */
    private String ocrUrl = "http://127.0.0.1:5000/";

    /** 登录 extParams 的设备信息（须与大厅微端 cookie 一致）。 */
    private String deviceInfo = "50:EB:F6:24:FF:09";

    /** 微端版本号（extParams.microVersion）。 */
    private String microVersion = "1.0.1.475";

    public String getOcrUrl() {
        return ocrUrl;
    }

    public void setOcrUrl(String ocrUrl) {
        this.ocrUrl = ocrUrl;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    public String getMicroVersion() {
        return microVersion;
    }

    public void setMicroVersion(String microVersion) {
        this.microVersion = microVersion;
    }

    // ── 出站代理：游戏 TCP 走本地 sing-box SOCKS5（香港住宅出口），绕过海外机房 IP 风控。已彻底移除熊猫代理。──
    // 端口 [socksPort, socksPort+socksPortCount) 轮询，多 inbound 对应多出口 IP 提升并发。
    private boolean proxyEnabled = false;
    private String proxySocksHost = "";
    private int proxySocksPort = 2333;
    private int proxySocksPortCount = 1;

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public String getProxySocksHost() {
        return proxySocksHost;
    }

    public void setProxySocksHost(String proxySocksHost) {
        this.proxySocksHost = proxySocksHost;
    }

    public int getProxySocksPort() {
        return proxySocksPort;
    }

    public void setProxySocksPort(int proxySocksPort) {
        this.proxySocksPort = proxySocksPort;
    }

    public int getProxySocksPortCount() {
        return proxySocksPortCount;
    }

    public void setProxySocksPortCount(int proxySocksPortCount) {
        this.proxySocksPortCount = proxySocksPortCount;
    }

    // ── 抓榜出站代理：熊猫动态住宅 SOCKS5（仅排行榜抓取用；玩家查询常热连接仍走 sing-box）。──
    // 经 bgl 提取 API 拉大陆住宅 IP，多 IP 分摊并发、各自独享带宽，根治单 IP 带宽被打满。
    private boolean pandaEnabled = false;
    private String pandaApiUrl = "";
    /** 固定 IP 池大小（所有抓榜连接轮询复用这几个 IP，过期才换）；= 有效并发 IP 数。 */
    private int pandaPoolSize = 15;
    /** 每次 API 批量提取的 IP 个数（不限量套餐单次最多 5×N；10 秒一次，故批量拉进缓冲复用）。 */
    private int pandaPullCount = 5;
    /** 每日提取 IP 数硬上限；<=0 表示不限（不限量套餐）。限量套餐才设正数（按北京午夜重置）。 */
    private int pandaDailyBudget = 0;
    /** 单个 IP 槽存活时长毫秒（熊猫 IP 2-10 分钟、平均 5 分钟，留余量取 4 分钟，过期才换）。 */
    private long pandaIpTtlMs = 240_000L;
    /** 两次提取最小间隔毫秒（不限量套餐 API 限 10 秒一次）。 */
    private long pandaMinPullIntervalMs = 10_000L;

    public boolean isPandaEnabled() {
        return pandaEnabled;
    }

    public void setPandaEnabled(boolean pandaEnabled) {
        this.pandaEnabled = pandaEnabled;
    }

    public String getPandaApiUrl() {
        return pandaApiUrl;
    }

    public void setPandaApiUrl(String pandaApiUrl) {
        this.pandaApiUrl = pandaApiUrl;
    }

    public int getPandaPoolSize() {
        return pandaPoolSize;
    }

    public void setPandaPoolSize(int pandaPoolSize) {
        this.pandaPoolSize = pandaPoolSize;
    }

    public int getPandaPullCount() {
        return pandaPullCount;
    }

    public void setPandaPullCount(int pandaPullCount) {
        this.pandaPullCount = pandaPullCount;
    }

    public int getPandaDailyBudget() {
        return pandaDailyBudget;
    }

    public void setPandaDailyBudget(int pandaDailyBudget) {
        this.pandaDailyBudget = pandaDailyBudget;
    }

    public long getPandaIpTtlMs() {
        return pandaIpTtlMs;
    }

    public void setPandaIpTtlMs(long pandaIpTtlMs) {
        this.pandaIpTtlMs = pandaIpTtlMs;
    }

    public long getPandaMinPullIntervalMs() {
        return pandaMinPullIntervalMs;
    }

    public void setPandaMinPullIntervalMs(long pandaMinPullIntervalMs) {
        this.pandaMinPullIntervalMs = pandaMinPullIntervalMs;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    public void setCallTimeoutSeconds(int callTimeoutSeconds) {
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    public Map<String, String> getServers() {
        return servers;
    }

    public void setServers(Map<String, String> servers) {
        this.servers = servers;
    }
}
