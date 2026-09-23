package com.example.starter.firmware.domain;

/**
 * 多跳版本回退计划。
 *
 * @param id                      回退计划ID
 * @param planKey                 计划业务键，全局唯一，由调用方提供
 * @param sourceReleaseId         来源投放发布单ID，必须为已结束（CANCELLED）状态
 * @param model                   目标设备型号
 * @param targetVersion           回退目标旧固件版本
 * @param status                  计划状态
 * @param sampleFloor             每跳失败率统计样本下限，取值2~100
 * @param failureThresholdPercent 每跳失败率阈值百分比，取值1~100
 * @param monitorRound            当前监控轮次，从1开始，人工恢复后加一
 * @param pausedHopIndex          当前暂停所在跳次（1~5）；非 PAUSED 时为 null
 * @param roundSuccess            当前监控轮次内首次进入 SUCCESS 的回跳任务数
 * @param roundFailed             当前监控轮次内首次进入 FAILED 的回跳任务数
 */
public record RollbackPlan(long id, String planKey, long sourceReleaseId, String model,
                           String targetVersion, RollbackPlanStatus status,
                           int sampleFloor, int failureThresholdPercent, int monitorRound,
                           Integer pausedHopIndex, int roundSuccess, int roundFailed) {
}
