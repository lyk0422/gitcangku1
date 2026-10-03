package com.example.starter.plan.model;

/**
 * 计划接续风险标记（当前状态，非历史）：NONE 正常；
 * CONNECTION_AT_RISK 表示至少一条生效接续的保障分钟数不足，是批量保障改签的候选。
 * 已开始计划不会被自动标记；风险解除或保障改签成功后清除。
 */
public enum ConnectionRisk {
    NONE,
    CONNECTION_AT_RISK
}