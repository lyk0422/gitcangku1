package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 已发布替代策略的只读视图，规则按发布顺序、候选按优先级升序排列。
 */
public record PolicyResponse(
        long version,
        Instant createdAt,
        List<RuleView> rules) {

    /**
     * 策略规则视图。
     */
    public record RuleView(
            int ruleOrder,
            String source,
            String platform,
            Instant effectiveAt,
            List<CandidateView> candidates) {
    }

    /**
     * 替代候选视图。
     */
    public record CandidateView(String coordinate, int priority) {
    }
}
