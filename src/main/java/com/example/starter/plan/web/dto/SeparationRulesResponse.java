package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 互斥规则集响应：当前规则集版本（整体替换成功一次加一）与规则清单（按类别对字典序）。
 */
public record SeparationRulesResponse(int ruleVersion, List<SeparationRuleView> rules) {
}
