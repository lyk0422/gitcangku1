package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceFailureRound;

/**
 * 设备版本失败轮次视图：展示本轮连续失败次数、当前状态、隔离依据（两次任务/版本/原因/确认人）
 * 与解除记录。时间均为 UTC ISO-8601；未发生或未上报的字段为 null。
 */
public record FailureRoundView(long roundId, String toVersion, int roundNo, int consecutiveFailed,
                               String status, Long firstTaskId, Long secondTaskId,
                               String firstFailureReason, String secondFailureReason,
                               String confirmedBy, String quarantinedAtUtc,
                               String releasedBy, String releaseReason, String releasedAtUtc) {

    public static FailureRoundView of(DeviceFailureRound round) {
        return new FailureRoundView(round.id(), round.toVersion(), round.roundNo(),
                round.consecutiveFailed(), round.status().name(),
                round.firstTaskId(), round.secondTaskId(),
                round.firstFailureReason(), round.secondFailureReason(),
                round.confirmedBy(), round.quarantinedAtUtc(),
                round.releasedBy(), round.releaseReason(), round.releasedAtUtc());
    }
}
