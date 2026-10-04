package com.example.starter.plan.model;

/**
 * 接续历史事件类型：REGISTERED 登记 / RISK 风险判定 / RESCHEDULED 保障改签决策 /
 * CANCELLED 取消。事件只追加不改写，按 id 升序固化时序。
 */
public enum ConnectionEventType {
    REGISTERED,
    RISK,
    RESCHEDULED,
    CANCELLED
}