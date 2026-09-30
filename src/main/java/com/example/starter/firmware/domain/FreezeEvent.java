package com.example.starter.firmware.domain;

/**
 * 版本冻结审计流水，只增不改：冻结、申请恢复、审批恢复各一条。
 *
 * @param id        流水ID
 * @param freezeId  所属冻结记录ID
 * @param version   被冻结的目标固件版本
 * @param eventType 动作：FROZEN / RESUME_REQUESTED / RESUME_APPROVED
 * @param operator  操作人（冻结创建人、恢复申请人或审批人）
 * @param reason    本次动作原因（审批动作沿用恢复申请原因）
 * @param eventAtUtc 动作发生时刻，UTC，ISO-8601 格式
 */
public record FreezeEvent(long id, long freezeId, String version, FreezeEventType eventType,
                          String operator, String reason, String eventAtUtc) {
}
