package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceFailureRound;

import java.util.List;

/**
 * 设备隔离状态视图：本轮连续失败次数、当前是否隔离、隔离依据、解除记录与全部历史轮次。
 * 历史轮次只增不删：解除隔离后旧的两次失败仍可在 rounds 中查到。
 *
 * @param deviceId            设备ID
 * @param version             最近一轮对应的目标固件版本，无任何失败历史为 null
 * @param roundNo             最近一轮序号，无历史为 0
 * @param consecutiveFailures 最近一轮连续失败次数，无历史为 0
 * @param quarantined         当前是否存在 QUARANTINED 轮次
 * @param basis               当前隔离依据（取最近一条 QUARANTINED 轮次），未隔离为 null
 * @param releases            全部已解除轮次的解除记录，按时间先后排列
 * @param rounds              全部版本的连续失败轮次明细，版本变化时各自独立、不重算
 */
public record DeviceQuarantineView(String deviceId, String version, int roundNo, int consecutiveFailures,
                                   boolean quarantined, QuarantineBasis basis,
                                   List<ReleaseRecordView> releases, List<RoundView> rounds) {

    /**
     * 隔离依据：固化触发隔离的两次任务、版本、失败原因、确认人与时刻。
     */
    public record QuarantineBasis(String version, int roundNo,
                                  long firstTaskId, String firstReason,
                                  long secondTaskId, String secondReason,
                                  String confirmedBy, String quarantinedAtUtc) {

        public static QuarantineBasis of(DeviceFailureRound round) {
            return new QuarantineBasis(round.version(), round.roundNo(),
                    round.firstTaskId(), round.firstReason(),
                    round.secondTaskId(), round.secondReason(),
                    round.confirmedBy(), round.quarantinedAtUtc());
        }
    }

    /**
     * 解除记录：审核人必须不同于隔离确认人；解除只开启新一轮投放，不清除历史失败次数。
     */
    public record ReleaseRecordView(String version, int roundNo, String releasedBy,
                                    String reason, String releasedAtUtc) {

        public static ReleaseRecordView of(DeviceFailureRound round) {
            return new ReleaseRecordView(round.version(), round.roundNo(), round.releasedBy(),
                    round.releaseReason(), round.releasedAtUtc());
        }
    }

    /**
     * 单个连续失败轮次明细，历史不可改。
     */
    public record RoundView(String version, int roundNo, int consecutiveFailures, String status,
                            long firstTaskId, String firstReason,
                            Long secondTaskId, String secondReason,
                            String confirmedBy, String quarantinedAtUtc,
                            ReleaseRecordView release) {

        public static RoundView of(DeviceFailureRound round) {
            return new RoundView(round.version(), round.roundNo(), round.consecutiveFailures(),
                    round.status().name(), round.firstTaskId(), round.firstReason(),
                    round.secondTaskId(), round.secondReason(),
                    round.confirmedBy(), round.quarantinedAtUtc(),
                    round.releasedAtUtc() == null ? null : ReleaseRecordView.of(round));
        }
    }
}
