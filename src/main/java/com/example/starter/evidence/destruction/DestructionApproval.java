package com.example.starter.evidence.destruction;

import java.time.LocalDateTime;

/**
 * 销毁令审批记录实体，对应 destruction_approval 表。记录只追加、不可变。
 *
 * @param id             主键
 * @param destructionKey 所属销毁令业务键
 * @param approverId     审批操作人；两人互不相同且都不同于提交人
 * @param decision       审批决定：AGREED / REJECTED
 * @param reason         审批备注或拒绝原因；拒绝时非空
 * @param createdAt      审批提交时间（Asia/Shanghai）
 */
public record DestructionApproval(
        Long id,
        String destructionKey,
        String approverId,
        ApprovalDecision decision,
        String reason,
        LocalDateTime createdAt) {
}
