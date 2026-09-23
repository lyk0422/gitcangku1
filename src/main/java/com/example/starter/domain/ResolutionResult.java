package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次锁定解析结果：最终精确版本组合、替代解释步骤与冻结的策略版本号。
 *
 * @param solution      名称 -> 精确版本（名称升序）
 * @param steps         替代步骤，按发生顺序
 * @param policyVersion 解析快照使用的唯一策略版本号；无策略时为 null
 */
public record ResolutionResult(
        Map<String, Integer> solution,
        List<SubstitutionStep> steps,
        Long policyVersion) {
}
