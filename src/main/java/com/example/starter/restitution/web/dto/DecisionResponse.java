package com.example.starter.restitution.web.dto;

import java.util.List;

/**
 * 裁决结果视图：终态案件版本、选定主张、藏品到申请人冻结关系、有效证据与批准快照。
 */
public record DecisionResponse(
        String caseKey,
        String status,
        long version,
        List<FrozenClaimView> claims,
        List<FrozenArtifactView> artifacts,
        List<FrozenEvidenceView> evidence,
        List<FrozenApprovalView> approvals
) {
}
