package com.example.starter.plan.model;

/**
 * 危险品互斥规则集版本快照。
 *
 * @param version    规则集版本号，从 1 开始；无配置时上层以版本 0 + 空列表表示
 * @param rules      规范化（类别对字母序、按类别对排序、去重）后的互斥规则
 * @param rulesHash  规则集规范化 JSON 的 SHA-256
 * @param operator   提交操作者
 * @param createdAt  版本创建时刻，UTC 毫秒
 */
public record HazardRuleSet(int version, java.util.List<HazardRule> rules, String rulesHash,
                            String operator, long createdAt) {
}
