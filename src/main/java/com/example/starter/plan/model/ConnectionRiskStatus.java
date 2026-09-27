package com.example.starter.plan.model;

/**
 * 接续保障状态（后车计划当前的接续风险态）：
 * NONE 无生效接续或保障充足；AT_RISK 前车延后导致至少一条接续保障不足。
 * 该标记不改变计划的发布/取消主状态，仅用于风险筛选与保障改签裁决。
 */
public enum ConnectionRiskStatus {
    NONE,
    AT_RISK
}
