package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 依赖替代策略的一个发布版本：规则集合整体激活。
 *
 * @param policyVersion 策略版本号（自增主键）
 * @param policyKey     客户端提供的策略业务键，全局唯一
 * @param createdAt     激活时间，UTC
 * @param rules         策略内全部规则，按 ruleIndex 升序
 */
public record SubstitutionPolicy(
        long policyVersion,
        String policyKey,
        Instant createdAt,
        List<SubstitutionRule> rules) {
}
