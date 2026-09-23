package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一个版本化的替代策略：规则集合整体激活，policyVersion 单调递增、不可变。
 *
 * @param version   策略版本号（policyVersion）
 * @param createdAt 发布 UTC 时刻
 * @param rules     策略内全部规则（按发布顺序）
 */
public record SubstitutionPolicy(
        long version,
        Instant createdAt,
        List<SubstitutionRule> rules) {

    public SubstitutionPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 尚未发布过任何策略时的空策略（version 为 null 语义由调用方处理）。 */
    public static SubstitutionPolicy empty() {
        return new SubstitutionPolicy(0L, Instant.EPOCH, List.of());
    }
}
