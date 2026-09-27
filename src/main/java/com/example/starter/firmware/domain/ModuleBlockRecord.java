package com.example.starter.firmware.domain;

/**
 * 模块兼容阻断记录：拉取时设备模块不满足目标固件完整依赖集合且未被豁免。
 * 阻断不创建任务、不计入失败率；记录只增不改。
 *
 * @param id               阻断记录ID
 * @param releaseId        发布单ID
 * @param deviceId         设备ID
 * @param matrixVersion    阻断时兼容矩阵版本快照
 * @param exemptionVersion 阻断时发布单豁免版本快照
 * @param moduleDigest     阻断时设备模块摘要快照，设备从未上报为 null
 * @param blockedAtUtc     阻断时刻，UTC，ISO-8601
 */
public record ModuleBlockRecord(long id, long releaseId, String deviceId, int matrixVersion,
                                int exemptionVersion, String moduleDigest, String blockedAtUtc) {
}
