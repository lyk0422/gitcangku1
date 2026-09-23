package com.example.starter.restitution.domain;

/**
 * 主张行。
 *
 * @param id          主张自增主键
 * @param caseId      案件编号
 * @param claimKey    案内唯一主张键
 * @param applicant   申请人
 * @param statement   主张说明（不可改）
 * @param withdrawn   是否已撤回（不可恢复）
 * @param version     主张证据版本，初始 0，证据追加/撤销加一
 * @param createdAt   登记时间（epoch 毫秒）
 * @param withdrawnAt 撤回时间（epoch 毫秒），未撤回为 null
 */
public record ClaimRow(
        long id,
        String caseId,
        String claimKey,
        String applicant,
        String statement,
        boolean withdrawn,
        long version,
        long createdAt,
        Long withdrawnAt) {
}
