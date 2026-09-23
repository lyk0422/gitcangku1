package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁图解释中的一个替代步骤快照，历史不可改写。
 */
public record SubstitutionStepResponse(
        int stepIndex,
        String originalName,
        int originalVersion,
        int ruleIndex,
        String sourcePattern,
        String finalName,
        int finalVersion,
        long policyVersion,
        List<RejectedCandidateView> rejectedCandidates) {

    /**
     * 被拒绝候选及其原因，按尝试顺序。
     */
    public record RejectedCandidateView(String name, int version, String reason) {
    }
}
