package com.example.starter.race.domain;

/**
 * 处罚申诉状态。
 *
 * <ul>
 *   <li>{@link #PENDING}：已受理冻结，等待两名干事裁决；</li>
 *   <li>{@link #UPHELD}：两干事确认 UPHOLD，维持原处罚；</li>
 *   <li>{@link #REMOVED}：两干事确认 REMOVE，撤销原处罚；</li>
 *   <li>{@link #REPLACED}：两干事确认 REPLACE，旧处罚被新版本替代；</li>
 *   <li>{@link #REJECTED}：第二干事驳回第一干事建议。</li>
 * </ul>
 */
public enum AppealStatus {
    PENDING,
    UPHELD,
    REMOVED,
    REPLACED,
    REJECTED
}
