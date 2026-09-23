package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 策略版本视图，规则按策略内序号稳定排序。
 */
public record PolicyResponse(
        long policyVersion,
        String policyKey,
        Instant createdAt,
        List<RuleView> rules) {

    /**
     * 规则视图：替代坐标按优先级排序。
     */
    public record RuleView(
            int ruleIndex,
            String sourcePattern,
            String targetPlatform,
            Instant effectiveAt,
            List<CoordinateView> alternatives) {
    }

    /**
     * 精确坐标视图。
     */
    public record CoordinateView(String name, int version) {
    }
}
