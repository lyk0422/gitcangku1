package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 互斥能力规则变更结果。
 *
 * @param repositoryVersion 该次规则变更完成后的仓库版本号
 * @param createdAt         变更时间，UTC 时间戳
 */
public record ExclusionRuleResponse(
        String capabilityA,
        String capabilityB,
        long repositoryVersion,
        Instant createdAt) {
}