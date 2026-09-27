package com.example.starter.firmware.domain;

/**
 * 标签字典修订事件（只增不改）。仅当一次修订实际引入新标签时生成事件并推进字典版本。
 *
 * @param id           修订事件ID
 * @param eventType    修订来源：DEVICE_REGISTER 设备登记；LABEL_BATCH_UPDATE 批量标签修改
 * @param operator     操作者标识；未提供为 null
 * @param deviceCount  本次修订涉及的去重设备数（要求值）
 * @param newTagCount  本次修订实际引入的新标签数（实际值）
 * @param dictVersion  修订提交后的标签字典版本
 * @param revisedAtUtc 修订提交时刻，UTC，ISO-8601 格式
 */
public record LabelRevision(long id, String eventType, String operator, int deviceCount, int newTagCount,
                            long dictVersion, String revisedAtUtc) {
}
