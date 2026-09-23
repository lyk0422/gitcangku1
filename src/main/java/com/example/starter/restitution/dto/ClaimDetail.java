package com.example.starter.restitution.dto;

import java.util.List;

/**
 * 主张明细视图（含证据与批准历史）。
 *
 * @param claimKey        案内唯一主张键
 * @param applicant       申请人
 * @param statement       主张说明（登记后不可改）
 * @param withdrawn       是否已撤回（不可恢复）
 * @param evidenceVersion 当前证据版本
 * @param items           主张覆盖藏品
 * @param evidences       证据列表（含已撤销历史）
 * @param approvals       各版本批准列表
 */
public record ClaimDetail(
        String claimKey,
        String applicant,
        String statement,
        boolean withdrawn,
        long evidenceVersion,
        List<String> items,
        List<EvidenceView> evidences,
        List<ApprovalView> approvals) {
}
