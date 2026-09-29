package com.example.starter.firmware.domain;

/**
 * 固件回滚演练记录，历史只增不改。
 *
 * @param id               演练ID
 * @param operator         发起操作者
 * @param model            规范设备型号
 * @param targetVersion    目标版本（待发布版本），开始时设备必须处于该版本
 * @param rollbackVersion  回退版本（正式投放来源版本），与目标版本不同
 * @param status           演练状态：RUNNING/PASSED/FAILED
 * @param deviceCount      规范设备集合大小（要求设备数），开始时固定
 * @param successCount     回退成功设备数（实际值），RUNNING 时为 0
 * @param failedCount      回退失败设备数（实际值），RUNNING 时为 0
 * @param startedAtUtc     开始时刻，UTC，ISO-8601，区间左端点（含）
 * @param finishedAtUtc    结束时刻，UTC，ISO-8601，区间右端点（不含）；RUNNING 时为 null
 * @param gateDeniedReason 未授予门禁原因（DEVICE_FAILED/FINISHED_BEFORE_START）；PASSED 或 RUNNING 时为 null
 */
public record RollbackRehearsal(long id, String operator, String model, String targetVersion,
                                String rollbackVersion, RehearsalStatus status, int deviceCount,
                                int successCount, int failedCount, String startedAtUtc,
                                String finishedAtUtc, String gateDeniedReason) {
}
