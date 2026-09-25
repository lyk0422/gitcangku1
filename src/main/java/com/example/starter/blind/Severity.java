package com.example.starter.blind;

/**
 * 不良事件严重度。
 */
public enum Severity {
    /** 轻度：仅记录，不改变分配状态。 */
    MILD,
    /** 中度：仅记录，不改变分配状态。 */
    MODERATE,
    /** 重度：自动将分配标记为 URGENT_REVIEW，可走紧急揭盲通道。 */
    SEVERE
}
