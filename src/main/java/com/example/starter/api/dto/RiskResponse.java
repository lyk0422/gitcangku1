package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 制品版本风险标记视图。
 *
 * @param name      依赖制品名称
 * @param version   依赖制品精确版本号
 * @param riskLevel 风险等级代码：NONE/LOW/MEDIUM/HIGH/CRITICAL
 * @param reason    风险来源或说明，未填写时为 null
 * @param createdAt 首次标记时间，UTC 时间戳
 * @param updatedAt 最近一次更新时间，UTC 时间戳
 */
public record RiskResponse(
        String name,
        int version,
        String riskLevel,
        String reason,
        Instant createdAt,
        Instant updatedAt) {
}
