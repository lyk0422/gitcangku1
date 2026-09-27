package com.example.starter.firmware.api;

import java.util.List;

/**
 * MODULE_BLOCKED 阻断记录视图。阻断不创建任务、不计入失败率。
 *
 * @param blockId          阻断记录ID
 * @param releaseId        所属发布单ID
 * @param deviceId         被阻断设备ID
 * @param requestId        触发阻断的拉取请求ID
 * @param matrixVersion    阻断时目标固件矩阵版本；目标固件未登记需求时为 null
 * @param exemptionVersion 阻断时设备组有效豁免版本；无有效豁免为 null
 * @param deviceSummary    阻断时设备模块摘要；设备从未上报为 null
 * @param missingModules   缺失/低版本模块明细，按模块名稳定排序
 * @param blockedAtUtc     阻断时刻，UTC，ISO-8601
 */
public record BlockRecordView(long blockId, long releaseId, String deviceId, String requestId,
                              Integer matrixVersion, Integer exemptionVersion,
                              List<ModuleEntryView> deviceSummary,
                              List<MissingModuleView> missingModules, int missingCount,
                              String blockedAtUtc) {
}
