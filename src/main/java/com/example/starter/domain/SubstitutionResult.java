package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次替代感知解析的结果：精确版本组合与冻结的替代步骤解释。
 *
 * @param versions      最终坐标 -> 精确版本（坐标升序）
 * @param steps         替代步骤（按发生顺序，稳定排序）
 * @param policyVersion 解析时读取的唯一策略版本；从未发布过策略时为 null
 */
public record SubstitutionResult(
        Map<String, Integer> versions,
        List<SubstitutionStep> steps,
        Long policyVersion) {
}
