package com.example.starter.restitution.domain;

/**
 * 证据行；撤销后行保留为历史。
 *
 * @param id          证据自增主键
 * @param caseId      案件编号
 * @param claimId     所属主张主键
 * @param evidenceKey 案内唯一证据键
 * @param summary     非空摘要
 * @param version     创建时主张所处的证据版本
 * @param active      是否有效
 * @param createdAt   创建时间（epoch 毫秒）
 * @param revokedAt   撤销时间（epoch 毫秒），未撤销为 null
 */
public record EvidenceRow(
        long id,
        String caseId,
        long claimId,
        String evidenceKey,
        String summary,
        long version,
        boolean active,
        long createdAt,
        Long revokedAt) {
}
