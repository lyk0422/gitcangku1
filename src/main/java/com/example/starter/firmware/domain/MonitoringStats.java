package com.example.starter.firmware.domain;

/**
 * 单个监控轮次的失败率统计。只统计本轮首次进入 SUCCESS/FAILED 的任务，CANCELLED 不计样本。
 *
 * @param successCount 本轮成功样本数
 * @param failureCount 本轮失败样本数
 */
public record MonitoringStats(long successCount, long failureCount) {

    /**
     * 本轮样本总数（成功+失败）。
     */
    public long sampleCount() {
        return successCount + failureCount;
    }

    /**
     * 是否达到自动暂停条件：样本数达到下限且 FAILED×100 >= 样本数×阈值。
     */
    public boolean reachesThreshold(int sampleFloor, int failureThresholdPercent) {
        long samples = sampleCount();
        return samples >= sampleFloor && failureCount * 100 >= samples * (long) failureThresholdPercent;
    }
}
