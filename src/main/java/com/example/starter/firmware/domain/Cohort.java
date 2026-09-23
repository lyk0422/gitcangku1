package com.example.starter.firmware.domain;

/**
 * 投放队列，携带固件版本、区域配额、设备上限与灰度百分比策略。
 *
 * @param id                      队列ID
 * @param campaignId              所属投放活动ID
 * @param code                    队列编码，活动内唯一
 * @param firmwareVersion         队列固件版本，迁移要求源队列与目标队列一致
 * @param region                  所属区域
 * @param regionQuota             区域配额：本队列允许容纳的设备数上限（按区域口径）
 * @param deviceCap               设备上限：本队列允许容纳的设备总数上限
 * @param grayPercent             灰度百分比，取值1~100；队列规模不得超过活动内设备总数×grayPercent/100
 * @param deviceCount             当前队列规模（设备数）
 * @param status                  状态：ACTIVE 投放中，PAUSED 失败率自动暂停
 * @param successCount            累计确认安装成功的设备数，迟到回执不计入
 * @param monitorRound            当前监控轮次，从1开始，人工恢复后加一
 * @param roundSuccess            当前监控轮次内按当前代次结算的 SUCCESS 回执数
 * @param roundFailed             当前监控轮次内按当前代次结算的 FAILED 回执数
 * @param sampleFloor             失败率统计样本下限，取值2~100
 * @param failureThresholdPercent 失败率阈值百分比，取值1~100
 */
public record Cohort(long id, long campaignId, String code, String firmwareVersion, String region,
                     int regionQuota, int deviceCap, int grayPercent, int deviceCount,
                     CohortStatus status, int successCount, int monitorRound,
                     int roundSuccess, int roundFailed, int sampleFloor, int failureThresholdPercent) {
}
