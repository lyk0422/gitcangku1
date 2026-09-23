package com.example.starter.firmware.domain;

/**
 * 多跳回退计划。针对一张已结束（CANCELLED）投放单，按设备成功投放历史构造反向路径，
 * 以 hopIndex 为波次逐跳执行。
 *
 * @param id                        计划ID
 * @param planKey                   计划业务键，全局唯一
 * @param sourceReleaseId           来源已结束投放发布单ID
 * @param targetVersion             目标旧版本
 * @param status                    计划状态
 * @param currentHop               当前执行跳号，从0开始
 * @param maxHop                    计划内最大跳号（所有设备最长路径减1）
 * @param sampleFloor               每跳失败率统计样本下限，取值2~100
 * @param failureThresholdPercent   每跳失败率阈值百分比，取值1~100，失败率大于等于阈值即暂停
 * @param currentRound              当前跳内波次轮次，从1开始，人工恢复后加一，进入下一跳时重置为1
 * @param roundSuccess              当前（跳,轮）内首次回执 SUCCESS 的任务数
 * @param roundFailed               当前（跳,轮）内首次回执 FAILED 的任务数
 */
public record RollbackPlan(long id, String planKey, long sourceReleaseId, String targetVersion,
                           RollbackPlanStatus status, int currentHop, int maxHop,
                           int sampleFloor, int failureThresholdPercent,
                           int currentRound, int roundSuccess, int roundFailed) {
}
