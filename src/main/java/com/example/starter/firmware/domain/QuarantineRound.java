package com.example.starter.firmware.domain;

/**
 * 设备针对某一目标固件版本的连续失败/隔离轮次。版本变化轮次独立，历史不重算。
 *
 * @param id                  轮次记录ID
 * @param deviceId            设备ID
 * @param targetVersion       本轮目标固件版本（发布单 to_version）
 * @param roundNo             轮次序号，从1开始，每解除一次加一
 * @param consecutiveFailures 本轮连续失败次数（0~2）
 * @param status              轮次状态
 * @param firstTaskId         第一次失败任务ID，未失败为 null
 * @param firstReleaseId      第一次失败所属发布单ID，未失败为 null
 * @param firstReason         第一次失败原因，回执未填为 null
 * @param firstFailedAtUtc    第一次失败时刻（UTC ISO-8601），未失败为 null
 * @param secondTaskId        第二次失败任务ID，未隔离为 null
 * @param secondReleaseId     第二次失败所属发布单ID，未隔离为 null
 * @param secondReason        第二次失败原因，未隔离为 null
 * @param secondFailedAtUtc   第二次失败时刻（UTC ISO-8601），未隔离为 null
 * @param confirmedBy         隔离确认人（第二次失败回执提交操作人），未隔离为 null
 * @param quarantinedAtUtc    隔离生效时刻（UTC ISO-8601），未隔离为 null
 * @param releasedBy          解除审核人（必须不同于 confirmedBy），未解除为 null
 * @param releasedAtUtc       解除时刻（UTC ISO-8601），未解除为 null
 * @param releaseReason       解除审核原因，未解除为 null
 * @param nextRoundId         解除时开启的下一轮记录ID，未解除为 null
 */
public record QuarantineRound(long id, String deviceId, String targetVersion, int roundNo,
                              int consecutiveFailures, QuarantineStatus status,
                              Long firstTaskId, Long firstReleaseId, String firstReason, String firstFailedAtUtc,
                              Long secondTaskId, Long secondReleaseId, String secondReason,
                              String secondFailedAtUtc, String confirmedBy, String quarantinedAtUtc,
                              String releasedBy, String releasedAtUtc, String releaseReason, Long nextRoundId) {
}
