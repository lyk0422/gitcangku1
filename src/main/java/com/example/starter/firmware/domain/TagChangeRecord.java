package com.example.starter.firmware.domain;

/**
 * 设备标签批量变更历史记录（只增不改）。
 *
 * @param id           变更记录ID
 * @param dictVersion  本次变更后的标签字典版本
 * @param operator     操作者
 * @param deviceCount  本次变更涉及的设备数
 * @param changesJson  规范化变更内容快照（JSON）
 * @param changedAtUtc 变更时刻，UTC，ISO-8601格式
 */
public record TagChangeRecord(long id, long dictVersion, String operator, int deviceCount,
                              String changesJson, String changedAtUtc) {
}
