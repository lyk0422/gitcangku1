package com.example.starter.firmware.domain;

/**
 * 回退计划逐跳冻结定义：创建计划时按设备反向路径生成，创建后不可改。
 *
 * @param id              冻结跳定义ID
 * @param planId          所属回退计划ID
 * @param deviceId        设备ID
 * @param hopIndex        跳次，从1开始，按反向路径顺序编号
 * @param expectedVersion 本跳冻结的设备期望起始版本（回退前版本）
 * @param targetVersion   本跳冻结的目标版本（回退后版本）
 * @param sourceReleaseId 本跳逆向对应的原正向投放发布单ID（来源投放记录）
 */
public record RollbackHop(long id, long planId, String deviceId, int hopIndex,
                          String expectedVersion, String targetVersion, long sourceReleaseId) {
}
