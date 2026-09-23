package com.example.starter.restitution.web.dto;

import java.util.List;

/**
 * 主张视图：含状态、证据版本、藏品子集与当前版本批准的评审人集合。
 */
public record ClaimResponse(
        String claimKey,
        String applicant,
        String statement,
        String status,
        long evidenceVersion,
        List<String> artifactNos,
        List<String> currentApprovers
) {
}
