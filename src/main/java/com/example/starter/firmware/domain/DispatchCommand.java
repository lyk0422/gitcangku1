package com.example.starter.firmware.domain;

/**
 * 设备下发指令，同活动同设备至多一条未决指令。
 *
 * @param id         下发指令ID
 * @param campaignId 所属投放活动ID
 * @param deviceId   设备ID
 * @param cohortId   指令目标队列ID
 * @param generation 指令代次，与下达时的分配代次一致
 * @param status     PENDING 未决；SUPERSEDED 被迁移废弃；SETTLED 已按回执结算
 */
public record DispatchCommand(long id, long campaignId, String deviceId, long cohortId,
                              int generation, CommandStatus status) {
}
