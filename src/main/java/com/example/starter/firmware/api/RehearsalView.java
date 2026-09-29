package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseGate;
import com.example.starter.firmware.domain.RollbackRehearsal;

/**
 * 回滚演练视图。数量字段同时给出实际值、要求值与差额；时长为 UTC 区间实际秒数（左闭右开，可为负）。
 *
 * @param rehearsalId          演练ID
 * @param operator             发起操作者
 * @param model                规范设备型号
 * @param targetVersion        目标版本
 * @param rollbackVersion      回退版本
 * @param status               RUNNING/PASSED/FAILED
 * @param deviceCount          要求设备数
 * @param successCount         实际成功回退设备数
 * @param failedCount          实际失败设备数
 * @param requiredSuccessCount 要求成功数（等于规范集合大小）
 * @param missingSuccessCount  差额：要求成功数减实际成功数
 * @param startedAtUtc         开始时刻，UTC，ISO-8601（区间起点，含）
 * @param finishedAtUtc        结束时刻，UTC，ISO-8601（区间终点，不含）；RUNNING 时为 null
 * @param durationSeconds      实际时长秒数（finished - started，左闭右开；结束早于开始时为负）；RUNNING 时为 null
 * @param gateStatus           门禁结论：READY_FOR_RELEASE 或 null（RUNNING/FAILED 时）
 * @param gateDeniedReason     未授予门禁原因（DEVICE_FAILED/FINISHED_BEFORE_START），否则为 null
 */
public record RehearsalView(long rehearsalId, String operator, String model, String targetVersion,
                            String rollbackVersion, String status, int deviceCount, int successCount,
                            int failedCount, int requiredSuccessCount, int missingSuccessCount,
                            String startedAtUtc, String finishedAtUtc, Long durationSeconds,
                            String gateStatus, String gateDeniedReason) {

    public static RehearsalView of(RollbackRehearsal rehearsal, ReleaseGate gate) {
        Long duration = rehearsal.finishedAtUtc() == null ? null
                : java.time.Duration.between(java.time.Instant.parse(rehearsal.startedAtUtc()),
                        java.time.Instant.parse(rehearsal.finishedAtUtc())).toSeconds();
        return new RehearsalView(rehearsal.id(), rehearsal.operator(), rehearsal.model(),
                rehearsal.targetVersion(), rehearsal.rollbackVersion(), rehearsal.status().name(),
                rehearsal.deviceCount(), rehearsal.successCount(), rehearsal.failedCount(),
                rehearsal.deviceCount(), rehearsal.deviceCount() - rehearsal.successCount(),
                rehearsal.startedAtUtc(), rehearsal.finishedAtUtc(), duration,
                gate == null ? null : gate.status().name(), rehearsal.gateDeniedReason());
    }
}
