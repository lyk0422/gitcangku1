package com.example.starter.plan.model;

/**
 * 计划接续风险标记：NONE 无风险；CONNECTION_AT_RISK 表示至少一条生效接续保障不足，
 * 仅作标记不自动修改计划时刻，由批量保障改签或后续重算清除。
 */
public enum ConnectionRisk {
    NONE,
    CONNECTION_AT_RISK
}