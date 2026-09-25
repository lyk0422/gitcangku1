package com.example.starter.firmware.domain;

/**
 * 固件灰度发布单。
 *
 * @param id                      发布单ID
 * @param version                 发布单版本号，从1开始，每次扩量、人工恢复或跳级开关修改成功加一
 * @param model                   目标设备型号
 * @param fromVersion             来源固件版本
 * @param toVersion               目标固件版本，必须与来源版本不同
 * @param ratio                   投放比例，取值0~100，只增不减
 * @param status                  状态：ACTIVE 投放中，PAUSED 失败率自动暂停，CANCELLED 已取消（终态）
 * @param allowSkip               跳级开关：true 允许跳级（拉取时忽略前置链校验），false 按前置链校验
 * @param sampleFloor             失败率统计样本下限，取值2~100
 * @param failureThresholdPercent 失败率阈值百分比，取值1~100
 * @param monitorRound            当前监控轮次，从1开始，人工恢复后加一
 * @param roundSuccess            当前监控轮次内首次进入 SUCCESS 的任务数
 * @param roundFailed             当前监控轮次内首次进入 FAILED 的任务数
 */
public record ReleaseOrder(long id, int version, String model, String fromVersion, String toVersion,
                           int ratio, ReleaseStatus status, boolean allowSkip, int sampleFloor,
                           int failureThresholdPercent, int monitorRound, int roundSuccess,
                           int roundFailed) {
}
