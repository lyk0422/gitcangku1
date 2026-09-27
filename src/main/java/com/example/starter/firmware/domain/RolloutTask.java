package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 * 模块相关字段在任务创建（下发或阻断后重新放行）时固化，设备后续上报变化或矩阵/豁免修订不改写历史任务。
 *
 * @param id             任务ID
 * @param releaseId      所属发布单ID
 * @param deviceId       设备ID
 * @param status         任务状态
 * @param firstResult    首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param moduleSummary  下发时固化的设备模块摘要 JSON（按模块名排序）；设备从未上报为 null
 * @param matrixVersion  下发时目标固件兼容矩阵版本；目标固件未登记模块需求时为 null
 * @param exemptionVersion 下发时生效的设备组模块豁免版本；未使用豁免为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String moduleSummary, Integer matrixVersion,
                          Integer exemptionVersion) {
}
