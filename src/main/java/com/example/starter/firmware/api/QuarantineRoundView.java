package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.QuarantineRound;

/**
 * 设备某一目标版本的一轮连续失败/隔离记录视图，历史轮次全部保留。
 *
 * @param id                  轮次记录ID
 * @param deviceId            设备ID
 * @param targetVersion       本轮目标固件版本
 * @param roundNo             轮次序号，从1开始
 * @param consecutiveFailures 本轮当前连续失败次数（0~2），成功后归零但失败依据保留
 * @param status              TRACKING/QUARANTINED/RELEASED
 * @param firstTaskId         第一次失败任务ID，未失败为 null
 * @param firstReleaseId      第一次失败所属发布单ID，未失败为 null
 * @param firstReason         第一次失败原因，回执未填为 null
 * @param firstFailedAtUtc    第一次失败时刻（UTC ISO-8601），未失败为 null
 * @param secondTaskId        第二次失败任务ID，未隔离为 null
 * @param secondReleaseId     第二次失败所属发布单ID，未隔离为 null
 * @param secondReason        第二次失败原因，未隔离为 null
 * @param secondFailedAtUtc   第二次失败时刻（UTC ISO-8601），未隔离为 null
 * @param confirmedBy         隔离确认人（第二次失败提交操作人），未隔离为 null
 * @param quarantinedAtUtc    隔离生效时刻（UTC ISO-8601），未隔离为 null
 * @param releasedBy          解除审核人，未解除为 null
 * @param releasedAtUtc       解除时刻（UTC ISO-8601），未解除为 null
 * @param releaseReason       解除审核原因，未解除为 null
 * @param nextRoundId         解除后开启的下一轮记录ID，未解除为 null
 */
public record QuarantineRoundView(long id, String deviceId, String targetVersion, int roundNo,
                                  int consecutiveFailures, String status,
                                  Long firstTaskId, Long firstReleaseId, String firstReason,
                                  String firstFailedAtUtc,
                                  Long secondTaskId, Long secondReleaseId, String secondReason,
                                  String secondFailedAtUtc, String confirmedBy, String quarantinedAtUtc,
                                  String releasedBy, String releasedAtUtc, String releaseReason,
                                  Long nextRoundId) {

    public static QuarantineRoundView of(QuarantineRound r) {
        return new QuarantineRoundView(r.id(), r.deviceId(), r.targetVersion(), r.roundNo(),
                r.consecutiveFailures(), r.status().name(), r.firstTaskId(), r.firstReleaseId(),
                r.firstReason(), r.firstFailedAtUtc(), r.secondTaskId(), r.secondReleaseId(),
                r.secondReason(), r.secondFailedAtUtc(), r.confirmedBy(), r.quarantinedAtUtc(),
                r.releasedBy(), r.releasedAtUtc(), r.releaseReason(), r.nextRoundId());
    }
}
