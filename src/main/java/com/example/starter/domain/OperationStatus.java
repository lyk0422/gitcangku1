package com.example.starter.domain;

/**
 * 授粉操作状态。
 */
public enum OperationStatus {
    /** 有效：占据花序，等待结果确认。 */
    EFFECTIVE,
    /** 已确认：结果已登记（成功或失败），终态，不可撤销。 */
    CONFIRMED,
    /** 已撤销：不再占据花序，窗口未过期时可重新授粉。 */
    REVOKED
}