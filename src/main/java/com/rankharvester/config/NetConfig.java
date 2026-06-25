package com.rankharvester.config;

import com.rankharvester.net.CredentialProvider;
import com.rankharvester.net.FlashvarsClient;
import com.rankharvester.net.FlashvarsCredentialProvider;
import com.rankharvester.net.GameClient;
import com.rankharvester.net.GameProperties;
import com.rankharvester.net.NettyGameClient;
import com.rankharvester.net.PandaProxyService;
import com.rankharvester.net.SingboxProxyService;
import com.rankharvester.net.SimulatedGameClient;
import com.rankharvester.net.UnconfiguredCredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 网络层装配：根据 dry-run 选择模拟 / 真实游戏客户端。 */
@Configuration
public class NetConfig {

    private static final Logger log = LoggerFactory.getLogger(NetConfig.class);

    /** flashvars 抓取客户端（直连）；登录凭据 + GameData 资源下载共用。 */
    @Bean
    public FlashvarsClient flashvarsClient(GameProperties props) {
        return new FlashvarsClient(props.getMicroVersion(), props.getDeviceInfo());
    }

    /** dry-run 用占位；真实模式用 flashvars 凭据（直连、无代理）。 */
    @Bean
    public CredentialProvider credentialProvider(GameProperties props, FlashvarsClient flashvars) {
        if (props.isDryRun()) {
            return new UnconfiguredCredentialProvider();
        }
        return new FlashvarsCredentialProvider(flashvars, props.getMicroVersion(), props.getDeviceInfo());
    }

    @Bean
    public SingboxProxyService singboxProxyService(GameProperties props) {
        return new SingboxProxyService(props);
    }

    /** 抓榜出口：熊猫动态住宅 SOCKS5（仅排行榜抓取连接用，注入 GameLoginService）。 */
    @Bean
    public PandaProxyService pandaProxyService(GameProperties props, ObjectMapper mapper) {
        return new PandaProxyService(props, mapper);
    }

    @Bean(destroyMethod = "")
    public GameClient gameClient(
            GameProperties props, CredentialProvider credentialProvider, SingboxProxyService proxyService) {
        if (props.isDryRun()) {
            log.warn("游戏客户端运行于 dry-run 模拟模式（不连真实游戏服）。接入真实环境请置 rankharvester.game.dry-run=false");
            return new SimulatedGameClient();
        }
        log.info("游戏客户端运行于真实 Netty 模式（flashvars 直连，验证码=断开，代理={}）", proxyService.enabled());
        return new NettyGameClient(props, credentialProvider, proxyService);
    }
}
