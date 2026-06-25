package com.rankharvester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * rank-harvester 入口。
 *
 * <p>排行榜抓取服务：游戏 TCP 握手/心跳 + APC 收发包 + 饥饿调度抓取 +
 * Redis 队列/缓存 + 嵌入式 DuckDB 快照 + 活动邮件订阅推送。
 */
// 同时扫描移植自 StrikeGod 的活动 SWF→长图 渲染管线（com.strikegod.engine.* 下仅含该套渲染/编排 bean）
@SpringBootApplication(scanBasePackages = {"com.rankharvester", "com.strikegod.engine"})
@EnableScheduling
@ConfigurationPropertiesScan
public class RankHarvesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RankHarvesterApplication.class, args);
    }
}
