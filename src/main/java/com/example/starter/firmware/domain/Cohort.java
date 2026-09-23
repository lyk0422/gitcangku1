package com.example.starter.firmware.domain;

/**
 * 投放队列（cohort）。
 *
 * @param id                        队列ID
 * @param releaseId                 所属投放活动ID
 * @param cohortCode                队列编码，同一活动内唯一
 * @param firmwareVersion           队列使用的固件版本
 * @param regionCode                队列所属区域编码
 * @param deviceCap                 队列设备上限，取值 &gt;= 1
 * @param canaryPercent             灰度百分比，取值 0~100
 * @param sampleFloor               队列失败率统计样本下限，取值 2~100
 * @param failureThresholdPercent   队列失败率阈值百分比，取值 1~100
 * @param successCount              当前监控轮次首次 SUCCESS 的回执数
 * @param failedCount               当前监控轮次首次 FAILED 的回执数
 * @param paused                    队列是否因失败率自动暂停
 */
public record Cohort(long id, long releaseId, String cohortCode, String firmwareVersion, String regionCode,
                     int deviceCap, int canaryPercent, int sampleFloor, int failureThresholdPercent,
                     int successCount, int failedCount, boolean paused) {
}
