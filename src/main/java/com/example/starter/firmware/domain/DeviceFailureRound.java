package com.example.starter.firmware.domain;

/**
 * 设备针对某一目标固件版本的连续失败轮次。同一设备同一版本连续两次失败后隔离；
 * 隔离解除或设备成功升级后再失败另开新一轮，历史行只增不删、不重算。
 *
 * @param id                  轮次记录ID
 * @param deviceId            设备ID
 * @param version             投放目标固件版本（发布单 to_version）
 * @param roundNo             同设备同版本的轮次序号，从1开始
 * @param consecutiveFailures 本轮连续失败次数，重复回执不重复计数
 * @param status              轮次状态
 * @param firstTaskId         本轮第一次失败任务ID
 * @param firstReason         第一次失败原因，设备未上报原因为 null
 * @param secondTaskId        本轮第二次失败（触发隔离）任务ID，未发生为 null
 * @param secondReason        第二次失败原因，设备未上报原因为 null
 * @param confirmedBy         隔离确认人（第二次失败回执提交人），未隔离为 null
 * @param quarantinedAtUtc    隔离时刻，UTC，ISO-8601 格式，未隔离为 null
 * @param releasedBy          解除审核人（必须不同于 confirmedBy），未解除为 null
 * @param releaseReason       解除审核原因，未解除为 null
 * @param releasedAtUtc       解除时刻，UTC，ISO-8601 格式，未解除为 null
 */
public record DeviceFailureRound(long id, String deviceId, String version, int roundNo,
                                 int consecutiveFailures, DeviceFailureRoundStatus status,
                                 Long firstTaskId, String firstReason,
                                 Long secondTaskId, String secondReason,
                                 String confirmedBy, String quarantinedAtUtc,
                                 String releasedBy, String releaseReason, String releasedAtUtc) {
}
