package com.example.starter.firmware.domain;

/**
 * 冻结恢复申请及其审批结果，每次申请独立成行，历史不可改。
 *
 * @param id               恢复申请记录ID
 * @param freezeId         所属冻结记录ID
 * @param requestedBy      恢复申请人
 * @param reason           恢复申请原因
 * @param requestedAtUtc   申请提交时刻，UTC，ISO-8601 格式
 * @param status           审批状态：PENDING 待审批；APPROVED 已由异于冻结人的审批人批准（冻结解除）
 * @param reviewedBy       审批人，未审批为 null；必须不同于冻结创建人
 * @param reviewedAtUtc    审批时刻，UTC，ISO-8601 格式；未审批为 null
 */
public record FreezeRestore(long id, long freezeId, String requestedBy, String reason, String requestedAtUtc,
                            RestoreStatus status, String reviewedBy, String reviewedAtUtc) {
}
