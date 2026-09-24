package com.example.starter.evidence.destruction.dto;

import com.example.starter.evidence.destruction.ApprovalDecision;

import java.time.LocalDateTime;

/**
 * 销毁令审批记录视图。
 *
 * @param approverId 审批操作人
 * @param decision   审批决定：AGREED / REJECTED
 * @param reason     审批备注或拒绝原因；未填写为 null
 * @param createdAt  审批提交时间（Asia/Shanghai）
 */
public record DestructionApprovalView(
        String approverId,
        ApprovalDecision decision,
        String reason,
        LocalDateTime createdAt) {
}
