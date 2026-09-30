package com.example.starter.firmware.domain;

/**
 * 设备对某一目标固件版本的连续失败轮次。版本变化时新一轮计数独立，历史轮次不重算。
 *
 * @param id                   轮次ID
 * @param deviceId             设备ID
 * @param toVersion            目标固件版本
 * @param roundNo              该设备该版本的轮次序号，从1开始
 * @param consecutiveFailed    本轮连续失败次数（1 或 2）
 * @param status               轮次状态：ACTIVE / QUARANTINED / RELEASED
 * @param firstTaskId          第一次失败的任务ID
 * @param secondTaskId         第二次失败的任务ID，即隔离触发任务
 * @param firstFailureReason   第一次失败原因，设备未上报为 null
 * @param secondFailureReason  第二次失败原因，设备未上报为 null
 * @param confirmedBy          隔离确认人（第二次失败回执操作人），未携带为 null
 * @param quarantinedAtUtc     隔离生效时刻，UTC ISO-8601；未隔离为 null
 * @param releasedBy           解除审核操作人，必须与 confirmedBy 不同；未解除为 null
 * @param releaseReason        解除审核原因；未解除为 null
 * @param releasedAtUtc        解除时刻，UTC ISO-8601；未解除为 null
 */
public record DeviceFailureRound(long id, String deviceId, String toVersion, int roundNo,
                                 int consecutiveFailed, RoundStatus status,
                                 Long firstTaskId, Long secondTaskId,
                                 String firstFailureReason, String secondFailureReason,
                                 String confirmedBy, String quarantinedAtUtc,
                                 String releasedBy, String releaseReason, String releasedAtUtc) {
}
