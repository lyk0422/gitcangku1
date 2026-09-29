package com.example.starter.firmware.domain;

/**
 * 演练规范设备集合中的一台设备及其回退结果。
 *
 * @param id           演练设备行ID
 * @param rehearsalId  所属演练ID
 * @param deviceId     设备ID
 * @param ordinal      规范集合中的排序序号（设备ID字典序，从0开始）
 * @param result       回退结果 SUCCESS/FAILED；未提交结果为 null（null 语义稳定）
 * @param recordedAtUtc 结果记录时刻，UTC，ISO-8601；未提交结果为 null
 */
public record RehearsalDevice(long id, long rehearsalId, String deviceId, int ordinal,
                              ReceiptResult result, String recordedAtUtc) {
}
