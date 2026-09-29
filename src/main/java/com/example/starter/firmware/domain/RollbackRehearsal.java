package com.example.starter.firmware.domain;

/**
 * 回滚演练单。历史证据不可变：后续设备状态变化不改写本记录。
 *
 * @param id              演练ID
 * @param model           演练目标设备型号
 * @param targetVersion   目标固件版本（正式投放的 to_version）
 * @param rollbackVersion 回退固件版本，必须与目标版本不同
 * @param status          演练状态：RUNNING / SUCCEEDED
 * @param operator        发起演练的操作者，参与幂等指纹
 * @param deviceCount     规范设备集合数量（去重排序后），即要求成功的设备数
 * @param successCount    实际成功回退设备数；未提交结果为 0
 * @param failedCount     实际失败设备数；未提交结果为 0
 * @param startedAtUtc    开始时刻，UTC，ISO-8601，区间左闭端点
 * @param endedAtUtc      结束时刻，UTC，ISO-8601，区间右端点（不含）；未提交结果为 null
 * @param durationSeconds 演练时长秒数（结束-开始，实际值）；未提交结果为 null
 */
public record RollbackRehearsal(long id, String model, String targetVersion, String rollbackVersion,
                                RehearsalStatus status, String operator, int deviceCount,
                                int successCount, int failedCount, String startedAtUtc,
                                String endedAtUtc, Long durationSeconds) {
}
