package com.example.starter.domain;

import java.util.List;

/**
 * 单跳替代的解释快照：原坐标、命中规则、按候选顺序的拒绝原因、最终坐标。
 *
 * @param original    本跳被替代的原坐标
 * @param ruleId      命中规则发布时主键
 * @param ruleIndex   命中规则在策略内序号
 * @param sourcePattern 命中规则的原坐标模式（冻结）
 * @param finalCoordinate 本跳最终采用坐标
 * @param rejected    被拒绝候选及其原因，按尝试顺序
 * @param policyVersion 冻结的策略版本号
 */
public record SubstitutionStep(
        Coordinate original,
        long ruleId,
        int ruleIndex,
        String sourcePattern,
        Coordinate finalCoordinate,
        List<RejectedCandidate> rejected,
        long policyVersion) {
}
