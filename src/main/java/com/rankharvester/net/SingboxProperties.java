package com.rankharvester.net;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sing-box 配置托管设置。用 {@code @ConfigurationProperties} 以获得宽松绑定
 * （env {@code RANKHARVESTER_SINGBOX_MANAGEENABLED} ↔ {@code manage-enabled}）。
 */
@ConfigurationProperties(prefix = "rankharvester.singbox")
public class SingboxProperties {

    /** 是否由后端按 DB 节点重生成 config.json 并 pkill 重载（本地/dry-run 关闭，避免误写 /etc/sing-box）。 */
    private boolean manageEnabled = false;

    /** 生成的 sing-box 配置文件路径（容器内 bind-mount 处）。 */
    private String configPath = "/etc/sing-box/config.json";

    /** clash_api external_controller 监听地址（供故障转移脚本）。 */
    private String clashApiListen = "127.0.0.1:9090";

    public boolean isManageEnabled() {
        return manageEnabled;
    }

    public void setManageEnabled(boolean manageEnabled) {
        this.manageEnabled = manageEnabled;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getClashApiListen() {
        return clashApiListen;
    }

    public void setClashApiListen(String clashApiListen) {
        this.clashApiListen = clashApiListen;
    }
}
