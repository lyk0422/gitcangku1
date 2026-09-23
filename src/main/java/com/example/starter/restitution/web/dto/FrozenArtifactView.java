package com.example.starter.restitution.web.dto;

/**
 * 藏品冻结关系视图：藏品编号 -> 判给的主张与申请人。
 */
public record FrozenArtifactView(
        String artifactNo,
        String claimKey,
        String applicant
) {
}
