package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次锁定解析的完整结果。
 *
 * @param entries          最终精确集合：名称 -> 精确版本（名称升序），含根、必选闭包与成功加入的可选制品
 * @param optionalDecisions 每条可选依赖按“来源名称、依赖名称”字典序的判定结果
 */
public record ResolutionResult(
        Map<String, Integer> entries,
        List<OptionalDecision> optionalDecisions) {
}
