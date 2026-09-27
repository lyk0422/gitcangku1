package com.example.starter.firmware.domain;

/**
 * 发布单模块豁免记录（按版本整体替换，历史版本保留）。
 *
 * @param id               豁免记录ID
 * @param releaseId        发布单ID
 * @param exemptionVersion 豁免版本，与发布单 exemptionVersion 对应
 * @param deviceGroup      适用设备组
 * @param module           豁免模块名
 * @param expiresAtUtc     到期时刻，UTC，ISO-8601；有效期左闭右开，到期自动失效
 * @param createdAtUtc     本版豁免登记时刻，UTC，ISO-8601
 */
public record ExemptionEntry(long id, long releaseId, int exemptionVersion, String deviceGroup,
                             String module, String expiresAtUtc, String createdAtUtc) {
}
