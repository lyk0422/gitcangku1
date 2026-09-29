package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseGate;

/**
 * 门禁结论视图（不可变证据）。
 */
public record GateView(long gateId, String model, String targetVersion, String rollbackVersion,
                       String status, long rehearsalId, String operator, String grantedAtUtc) {

    public static GateView of(ReleaseGate gate) {
        return new GateView(gate.id(), gate.model(), gate.targetVersion(), gate.rollbackVersion(),
                gate.status().name(), gate.rehearsalId(), gate.operator(), gate.grantedAtUtc());
    }
}
