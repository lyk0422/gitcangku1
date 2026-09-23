package com.example.starter.restitution.web.dto;

/**
 * 冻结有效证据快照视图。
 */
public record FrozenEvidenceView(
        String claimKey,
        String evidenceKey,
        String summary,
        long evidenceVersion
) {
}
