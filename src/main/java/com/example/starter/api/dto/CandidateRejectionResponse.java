package com.example.starter.api.dto;

/**
 * 替代候选拒绝原因的冻结快照。
 */
public record CandidateRejectionResponse(
        String coordinate,
        int priority,
        String reason) {
}
