package com.rankharvester.rank.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 调度器参数。 */
@ConfigurationProperties(prefix = "rankharvester.scheduler")
public class SchedulerProperties {

    /** 任务租约时长（秒）：领取后须在此时间内完成，否则被回收重派。 */
    private int leaseSeconds = 60;

    /** 每个调度 tick 最多领取的任务总数（节流，避免突刺）。 */
    private int maxBatchPerTick = 50;

    /** 每个 tick 最多回收的过期租约数。 */
    private int repairMaxPerTick = 200;

    /** WDRR 赤字上界系数：deficit 不超过 quantum * 此值，避免长期空闲后突发领取。 */
    private int deficitCapFactor = 3;

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxBatchPerTick() {
        return maxBatchPerTick;
    }

    public void setMaxBatchPerTick(int maxBatchPerTick) {
        this.maxBatchPerTick = maxBatchPerTick;
    }

    public int getRepairMaxPerTick() {
        return repairMaxPerTick;
    }

    public void setRepairMaxPerTick(int repairMaxPerTick) {
        this.repairMaxPerTick = repairMaxPerTick;
    }

    public int getDeficitCapFactor() {
        return deficitCapFactor;
    }

    public void setDeficitCapFactor(int deficitCapFactor) {
        this.deficitCapFactor = deficitCapFactor;
    }
}
