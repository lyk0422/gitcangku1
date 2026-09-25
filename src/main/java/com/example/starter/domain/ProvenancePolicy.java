package com.example.starter.domain;

import java.util.List;

/**
 * 来源策略的单个不可变版本。
 *
 * @param version      策略版本号，从 1 递增，不可原地改写
 * @param minLevel     要求的最低证明等级
 * @param allowedRepos 允许的来源仓标识集合（字典序升序，规范化后存储）
 */
public record ProvenancePolicy(
        int version,
        int minLevel,
        List<String> allowedRepos) {
}
