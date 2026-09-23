package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 单条替代规则：原坐标模式在指定目标平台、生效时刻之后命中，
 * 命中后按 alternatives 顺序（优先级从高到低）尝试 1～5 个替代坐标。
 *
 * @param id             规则行主键
 * @param ruleIndex      策略内序号（从 0 开始）
 * @param sourcePattern  原坐标匹配模式，支持 * 与 ? 通配
 * @param targetPlatform 目标平台，须与锁定请求平台完全一致
 * @param effectiveAt    生效 UTC 时刻
 * @param alternatives   按优先级排序的替代坐标，1～5 个
 */
public record SubstitutionRule(
        long id,
        int ruleIndex,
        String sourcePattern,
        String targetPlatform,
        Instant effectiveAt,
        List<Coordinate> alternatives) {
}
