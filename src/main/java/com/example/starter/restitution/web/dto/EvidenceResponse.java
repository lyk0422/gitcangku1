package com.example.starter.restitution.web.dto;

/**
 * 证据视图：撤销历史保留，status 表示当前是否有效。
 */
public record EvidenceResponse(
        String evidenceKey,
        String summary,
        String status,
        long evidenceVersion
) {
}
