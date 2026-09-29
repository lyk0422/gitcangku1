package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackRehearsal;

import java.util.List;

/**
 * 演练视图：头部信息（实际值/要求值/差额）与每台设备结果，按 ordinal 稳定排序。
 *
 * @param rehearsalId        演练ID
 * @param model              目标设备型号
 * @param targetVersion      目标固件版本
 * @param rollbackVersion    回退固件版本
 * @param status             演练状态 RUNNING / SUCCEEDED
 * @param operator           操作者
 * @param requiredDeviceCount 要求成功的设备数（规范集合数量）
 * @param actualSuccessCount 实际成功回退设备数
 * @param actualFailedCount  实际失败设备数
 * @param missingCount       差额=要求数-实际成功数（未提交或存在失败时为正）
 * @param startedAtUtc       开始时刻，UTC，ISO-8601
 * @param endedAtUtc         结束时刻，UTC，ISO-8601；未提交结果为 null
 * @param durationSeconds    演练时长秒数（实际值）；未提交结果为 null
 * @param devices            每台设备结果，按规范集合序号排序
 */
public record RehearsalView(long rehearsalId, String model, String targetVersion, String rollbackVersion,
                            String status, String operator, int requiredDeviceCount, int actualSuccessCount,
                            int actualFailedCount, int missingCount, String startedAtUtc, String endedAtUtc,
                            Long durationSeconds, List<DeviceResultView> devices) {

    public static RehearsalView of(RollbackRehearsal rehearsal, List<DeviceResultView> devices) {
        int missing = rehearsal.deviceCount() - rehearsal.successCount();
        return new RehearsalView(rehearsal.id(), rehearsal.model(), rehearsal.targetVersion(),
                rehearsal.rollbackVersion(), rehearsal.status().name(), rehearsal.operator(),
                rehearsal.deviceCount(), rehearsal.successCount(), rehearsal.failedCount(), missing,
                rehearsal.startedAtUtc(), rehearsal.endedAtUtc(), rehearsal.durationSeconds(), devices);
    }
}
