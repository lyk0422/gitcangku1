package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 危险品互斥规则集版本视图。无任何配置时 version 为 0、rules 为空列表、hash/operator/createdAt 为 null。
 */
public record HazardRuleSetView(int version, List<HazardRuleView> rules, String hash,
                                String operator, Instant createdAt) {
}
