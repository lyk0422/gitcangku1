package com.example.starter.firmware.domain;

import java.util.List;

/**
 * MODULE_BLOCKED 阻断诊断记录。阻断不创建任务、不计入失败率，历史不可改。
 *
 * @param id               阻断记录ID
 * @param releaseId        所属发布单ID
 * @param deviceId         被阻断设备ID
 * @param requestId        触发阻断的拉取请求ID
 * @param matrixVersion    阻断时目标固件矩阵版本；目标固件未登记需求时为 null
 * @param exemptionVersion 阻断时设备组有效豁免版本；无有效豁免为 null
 * @param deviceSummary    阻断时设备模块摘要 JSON（按模块名排序）；设备从未上报为 null
 * @param missingModules   缺失/低版本模块明细，按模块名稳定排序
 * @param blockedAtUtc     阻断时刻，UTC，ISO-8601
 */
public record ModuleBlockRecord(long id, long releaseId, String deviceId, String requestId,
                                Integer matrixVersion, Integer exemptionVersion, String deviceSummary,
                                List<MissingModule> missingModules, String blockedAtUtc) {
}
