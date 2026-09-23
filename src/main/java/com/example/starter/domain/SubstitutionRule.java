package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一条依赖替代规则：在指定平台上，命中源坐标模式的节点在无法直接解析时，
 * 按优先级（priority 升序）依次尝试候选坐标；effectiveAt（UTC）之前规则不生效。
 *
 * @param id          规则持久化主键（冻结解释用，未持久化时为 0）
 * @param source      原坐标模式
 * @param platform    目标平台
 * @param candidates  1～5 个按优先级排序的候选坐标
 * @param effectiveAt 生效 UTC 时刻
 */
public record SubstitutionRule(
        long id,
        CoordinatePattern source,
        String platform,
        List<SubstitutionCandidate> candidates,
        Instant effectiveAt) {

    public SubstitutionRule {
        platform = platform == null ? "" : platform.trim();
        if (platform.isEmpty()) {
            throw new IllegalArgumentException("目标平台不能为空");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("替代候选数量必须在 1～5 之间");
        }
        if (candidates.size() > 5) {
            throw new IllegalArgumentException("替代候选数量必须在 1～5 之间");
        }
        candidates = List.copyOf(candidates);
        if (effectiveAt == null) {
            throw new IllegalArgumentException("生效时刻不能为空");
        }
    }
}
