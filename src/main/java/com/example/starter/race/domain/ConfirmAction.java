package com.example.starter.race.domain;

/**
 * 第二名赛事干事的动作。
 * CONFIRM-确认与第一人完全相同的建议并执行裁决；REJECT-驳回建议，申诉回到PENDING。
 */
public enum ConfirmAction {
    CONFIRM,
    REJECT
}
