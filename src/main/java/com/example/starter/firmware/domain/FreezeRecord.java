package com.example.starter.firmware.domain;

/**
 * 目标固件版本的正式发布冻结记录。每个版本同一时刻至多一条 FROZEN 记录；
 * 审批恢复后置为 RESUMED，冻结区间（冻结至恢复时刻）保留，可再次冻结生成新记录。
 *
 * @param id                冻结记录ID
 * @param version           被冻结的目标固件版本（release_order.to_version）
 * @param status            状态：FROZEN 冻结生效中，RESUMED 已审批恢复
 * @param createdBy         冻结创建人（申请人）
 * @param reason            冻结原因，恢复失败不得清除
 * @param frozenAtUtc       冻结生效时刻，UTC，ISO-8601 格式
 * @param resumeRequestedBy 恢复申请人，未申请为 null
 * @param resumeReason      恢复申请原因，未申请为 null
 * @param resumeRequestedAtUtc 恢复申请时刻，UTC，未申请为 null
 * @param approvedBy        恢复审批人，必须与冻结创建人不同，未审批为 null
 * @param resumedAtUtc      审批恢复时刻，UTC，未审批为 null
 */
public record FreezeRecord(long id, String version, FreezeStatus status, String createdBy, String reason,
                           String frozenAtUtc, String resumeRequestedBy, String resumeReason,
                           String resumeRequestedAtUtc, String approvedBy, String resumedAtUtc) {
}
