package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseGate;

/**
 * 门禁结论视图。
 *
 * @param gateId               门禁记录ID
 * @param model                发布设备型号
 * @param targetVersion        发布目标固件版本
 * @param status               门禁结论：READY_FOR_RELEASE
 * @param rehearsalId          授予门禁的成功演练ID（业务证据）
 * @param requiredDeviceCount  要求成功的设备数
 * @param actualSuccessCount   实际成功回退设备数
 * @param grantedAtUtc         门禁授予时刻，UTC，ISO-8601
 */
public record GateView(long gateId, String model, String targetVersion, String status, long rehearsalId,
                       int requiredDeviceCount, int actualSuccessCount, String grantedAtUtc) {

    public static GateView of(ReleaseGate gate) {
        return new GateView(gate.id(), gate.model(), gate.targetVersion(), gate.status(), gate.rehearsalId(),
                gate.requiredDeviceCount(), gate.actualSuccessCount(), gate.grantedAtUtc());
    }
}
