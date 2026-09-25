package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FreezeOrder;

import java.util.List;

/**
 * 冻结令视图。windowActive 表示当前时刻是否处于生效窗口内（且未被撤销）；
 * frozenTaskIds 为当前仍被本冻结令冻结的任务；revokeAffectedTaskIds 为撤销时解冻的任务。
 */
public record FreezeOrderView(long freezeId, String freezeKey, int version,
                              List<String> models, List<Long> releaseIds,
                              String startUtc, String endUtc, String status, boolean windowActive,
                              EmergencyException exception, List<Long> frozenTaskIds,
                              String revokedAtUtc, List<Long> revokeAffectedTaskIds) {

    public static FreezeOrderView of(FreezeOrder order, boolean windowActive, List<Long> frozenTaskIds) {
        EmergencyException exception = order.exceptionIncidentId() == null ? null
                : new EmergencyException(order.exceptionIncidentId(), order.exceptionApprovers());
        return new FreezeOrderView(order.id(), order.freezeKey(), order.version(),
                order.models(), order.releaseIds(), order.startUtc(), order.endUtc(),
                order.status().name(), windowActive, exception, frozenTaskIds,
                order.revokedAtUtc(),
                order.revokeAffectedTaskIds() == null ? List.of() : order.revokeAffectedTaskIds());
    }
}
