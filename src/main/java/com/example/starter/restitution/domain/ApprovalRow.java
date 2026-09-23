package com.example.starter.restitution.domain;

/**
 * 批准行：某评审人对主张某证据版本的批准。
 *
 * @param id              自增主键
 * @param caseId          案件编号
 * @param claimId         主张主键
 * @param evidenceVersion 批准针对的证据版本
 * @param reviewer        评审人（不同于申请人）
 * @param createdAt       批准时间（epoch 毫秒）
 */
public record ApprovalRow(
        long id,
        String caseId,
        long claimId,
        long evidenceVersion,
        String reviewer,
        long createdAt) {
}
