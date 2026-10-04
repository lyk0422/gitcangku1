package com.example.starter.plan.model;

/**
 * 计划接续风险状态：NONE 无风险；CONNECTION_AT_RISK 接续保障不足，
 * 为批量保障改签候选。已开始或非已发布的计划不会被自动标记。
 */
public enum RiskStatus {
    NONE,
    CONNECTION_AT_RISK
}